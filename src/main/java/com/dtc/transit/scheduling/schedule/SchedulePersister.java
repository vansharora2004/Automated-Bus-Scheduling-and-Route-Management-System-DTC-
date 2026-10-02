package com.dtc.transit.scheduling.schedule;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.HandoverPlan;
import com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan;
import com.dtc.transit.scheduling.engine.model.ScheduleResult;

/**
 * Writes a run's output, all of it or none of it.
 *
 * <p>One transaction, and the caller must already be in one — {@code MANDATORY} rather than {@code REQUIRED},
 * so this can never accidentally commit a schedule on its own while the run that produced it goes on to fail.
 * A half-written schedule is worse than none: it looks runnable, and nothing in it says which half is missing.
 *
 * <p>Written with JDBC batches rather than JPA. A depot-day is a few hundred blocks and tens of thousands of
 * events; as managed entities that is a persistence context large enough to matter, for rows that are never
 * modified afterwards.
 */
@Service
public class SchedulePersister {

    private static final Logger log = LoggerFactory.getLogger(SchedulePersister.class);

    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final AppTimeProperties timeProperties;

    public SchedulePersister(JdbcTemplate jdbc, AppTimeProperties timeProperties) {
        this.jdbc = jdbc;
        this.timeProperties = timeProperties;
    }

    /**
     * Persists a finished result as a new draft schedule version.
     *
     * @return the new schedule's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long persist(UUID runId, Long depotId, LocalDate serviceDate, ScheduleResult result) {
        int versionNo = 1
                + jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_no), 0) FROM schedule WHERE depot_id = ? AND service_date = ?",
                        Integer.class,
                        depotId,
                        serviceDate);

        Long scheduleId = jdbc.queryForObject(
                """
                INSERT INTO schedule (run_id, depot_id, service_date, version_no, status)
                VALUES (?, ?, ?, ?, 'DRAFT')
                RETURNING id
                """,
                Long.class,
                runId,
                depotId,
                serviceDate,
                versionNo);

        Map<Integer, Long> blockIdByNo = insertBlocks(scheduleId, result.vehicleSchedule().blocks());
        insertEvents(serviceDate, result.vehicleSchedule().blocks(), blockIdByNo);
        insertAssignments(serviceDate, result.busAssignments(), blockIdByNo);
        Map<Integer, Long> dutyIdByNo = insertDuties(scheduleId, result.duties(), blockIdByNo);
        insertHandovers(scheduleId, result.handovers(), blockIdByNo, dutyIdByNo);
        insertConflicts(scheduleId, result.conflicts());

        log.info(
                "persisted schedule {} v{} for depot {} on {}: {} blocks, {} assignments, {} duties, "
                        + "{} handovers, {} conflicts",
                scheduleId,
                versionNo,
                depotId,
                serviceDate,
                result.vehicleSchedule().blocks().size(),
                result.busAssignments().size(),
                result.duties().size(),
                result.handovers().size(),
                result.conflicts().size());
        return scheduleId;
    }

    private Map<Integer, Long> insertBlocks(Long scheduleId, List<Block> blocks) {
        Map<Integer, Long> idByBlockNo = new HashMap<>();
        if (blocks.isEmpty()) {
            return idByBlockNo;
        }
        // Ids drawn from the sequence up front, so the events can reference their block inside one batch
        // instead of a round trip per block.
        List<Long> ids = jdbc.queryForList(
                "SELECT nextval('vehicle_block_seq') FROM generate_series(1, ?)", Long.class, blocks.size());

        List<Object[]> rows = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            idByBlockNo.put(block.blockNo(), ids.get(i));
            rows.add(new Object[] {
                ids.get(i),
                scheduleId,
                block.blockNo(),
                block.vehicleClass(),
                block.pullOutSec(),
                block.pullInSec(),
                block.serviceKm(),
                block.deadKm()
            });
        }
        batch(
                """
                INSERT INTO vehicle_block (id, schedule_id, block_no, vehicle_class, pull_out_sec, pull_in_sec,
                                           service_km, dead_km)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);
        return idByBlockNo;
    }

    private void insertEvents(LocalDate serviceDate, List<Block> blocks, Map<Integer, Long> blockIdByNo) {
        List<Object[]> rows = new ArrayList<>();
        for (Block block : blocks) {
            Long blockId = blockIdByNo.get(block.blockNo());
            for (BlockEvent event : block.events()) {
                rows.add(new Object[] {
                    blockId,
                    event.seq(),
                    serviceDate,
                    event.type().name(),
                    event.tripId(),
                    event.fromStopId(),
                    event.toStopId(),
                    event.startSec(),
                    event.endSec(),
                    event.distanceM(),
                    event.reliefOpportunity()
                });
            }
        }
        batch(
                """
                INSERT INTO block_event (block_id, seq, service_date, type, trip_id, from_stop_id, to_stop_id,
                                         start_sec, end_sec, distance_m, is_relief_opportunity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);
    }

    /**
     * Writes assignments as absolute instants.
     *
     * <p>Converted here rather than in the engine, which has no business knowing about time zones. The service
     * date's midnight in the presentation zone is the anchor, so a block ending at 25:30 lands correctly on the
     * following calendar day and the exclusion constraint can compare it with the next date's early block.
     */
    private void insertAssignments(
            LocalDate serviceDate, List<BusAssignmentPlan> assignments, Map<Integer, Long> blockIdByNo) {
        if (assignments.isEmpty()) {
            return;
        }
        ZoneId zone = timeProperties.zone();
        List<Object[]> rows = new ArrayList<>(assignments.size());
        for (BusAssignmentPlan plan : assignments) {
            Long blockId = blockIdByNo.get(plan.blockNo());
            if (blockId == null) {
                continue;
            }
            rows.add(new Object[] {
                blockId,
                plan.busId(),
                Timestamp.from(ServiceTime.toInstant(serviceDate, plan.fromSec(), zone)),
                Timestamp.from(ServiceTime.toInstant(serviceDate, plan.toSec(), zone)),
                "DRAFT"
            });
        }
        batch(
                """
                INSERT INTO bus_assignment (block_id, bus_id, starts_at, ends_at, schedule_status)
                VALUES (?, ?, ?, ?, ?)
                """,
                rows);
    }

    /**
     * Writes the duties, their pieces of work, and the link between them.
     *
     * <p>Three tables in dependency order, with ids drawn from their sequences up front so the children can
     * reference their parents inside a single batch rather than a round trip per row.
     *
     * @return each duty's database id, keyed by the duty number the engine gave it
     */
    private Map<Integer, Long> insertDuties(
            Long scheduleId, List<DutyPlan> duties, Map<Integer, Long> blockIdByNo) {

        Map<Integer, Long> dutyIdByNo = new HashMap<>();
        if (duties.isEmpty()) {
            return dutyIdByNo;
        }

        List<Long> dutyIds =
                jdbc.queryForList("SELECT nextval('duty_seq') FROM generate_series(1, ?)", Long.class, duties.size());
        int pieceCount = duties.stream().mapToInt(duty -> duty.pieces().size()).sum();
        List<Long> pieceIds = pieceCount == 0
                ? List.of()
                : jdbc.queryForList(
                        "SELECT nextval('piece_of_work_seq') FROM generate_series(1, ?)", Long.class, pieceCount);

        List<Object[]> dutyRows = new ArrayList<>(duties.size());
        List<Object[]> pieceRows = new ArrayList<>(pieceCount);
        List<Object[]> dutyPieceRows = new ArrayList<>(pieceCount);
        int pieceCursor = 0;

        for (int i = 0; i < duties.size(); i++) {
            DutyPlan duty = duties.get(i);
            Long dutyId = dutyIds.get(i);
            dutyIdByNo.put(duty.dutyNo(), dutyId);

            dutyRows.add(new Object[] {
                dutyId,
                scheduleId,
                duty.dutyNo(),
                duty.mode().name(),
                duty.dutyType().name(),
                duty.signOnSec(),
                duty.signOffSec(),
                duty.platformSec(),
                duty.paidSec(),
                duty.breakSec(),
                duty.spreadSec(),
                duty.overtimeSec()
            });

            for (int seq = 0; seq < duty.pieces().size(); seq++) {
                PieceOfWorkPlan piece = duty.pieces().get(seq);
                Long pieceId = pieceIds.get(pieceCursor++);
                Long blockId = blockIdByNo.get(piece.blockNo());
                if (blockId == null) {
                    // A duty whose block was not persisted cannot be stored, and silently writing a dangling
                    // piece would be worse than losing it loudly.
                    throw new IllegalStateException(
                            "duty " + duty.dutyNo() + " references block " + piece.blockNo() + ", which was not"
                                    + " persisted");
                }
                pieceRows.add(new Object[] {
                    pieceId,
                    blockId,
                    piece.fromEventSeq(),
                    piece.toEventSeq(),
                    piece.startSec(),
                    piece.endSec(),
                    piece.startReliefStopId(),
                    piece.endReliefStopId()
                });
                dutyPieceRows.add(new Object[] {dutyId, seq + 1, pieceId});
            }
        }

        batch(
                """
                INSERT INTO piece_of_work (id, block_id, from_event_seq, to_event_seq, start_sec, end_sec,
                                           start_relief_stop_id, end_relief_stop_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pieceRows);
        batch(
                """
                INSERT INTO duty (id, schedule_id, duty_no, mode, duty_type, sign_on_sec, sign_off_sec,
                                  platform_sec, paid_sec, break_sec, spread_sec, overtime_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dutyRows);
        batch("INSERT INTO duty_piece (duty_id, seq, piece_id) VALUES (?, ?, ?)", dutyPieceRows);
        return dutyIdByNo;
    }

    /**
     * Writes the crew changes between duties.
     *
     * <p>Skips any handover whose duties were not both persisted. That cannot happen from a well-formed engine
     * result, and a dangling handover would point a depot at a crew change that does not exist.
     */
    private void insertHandovers(
            Long scheduleId,
            List<HandoverPlan> handovers,
            Map<Integer, Long> blockIdByNo,
            Map<Integer, Long> dutyIdByNo) {

        if (handovers.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(handovers.size());
        for (HandoverPlan handover : handovers) {
            Long blockId = blockIdByNo.get(handover.blockNo());
            Long outgoing = dutyIdByNo.get(handover.outgoingDutyNo());
            Long incoming = dutyIdByNo.get(handover.incomingDutyNo());
            if (blockId == null || outgoing == null || incoming == null) {
                continue;
            }
            rows.add(new Object[] {
                scheduleId, blockId, handover.reliefStopId(), handover.atSec(), outgoing, incoming
            });
        }
        batch(
                """
                INSERT INTO handover (schedule_id, block_id, relief_stop_id, at_sec, outgoing_duty_id,
                                      incoming_duty_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                rows);
    }

    private void insertConflicts(Long scheduleId, List<EngineConflict> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(conflicts.size());
        for (EngineConflict conflict : conflicts) {
            rows.add(new Object[] {
                scheduleId, conflict.type(), conflict.severity().name(), refsAsJson(conflict.refs()),
                conflict.message()
            });
        }
        batch(
                """
                INSERT INTO conflict (schedule_id, type, severity, entity_refs, message)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """,
                rows);
    }

    /**
     * References as a JSON array, built by hand.
     *
     * <p>Hand-built rather than via Jackson because the shape is two fields and an array, and reaching for a
     * serialiser here would mean the engine's records growing annotations for the benefit of one writer.
     */
    private static String refsAsJson(List<EntityRef> refs) {
        if (refs.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"entityType\":\"")
                    .append(refs.get(i).entityType())
                    .append("\",\"entityId\":")
                    .append(refs.get(i).entityId())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int start = 0; start < rows.size(); start += BATCH) {
            jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + BATCH, rows.size())));
        }
    }
}
