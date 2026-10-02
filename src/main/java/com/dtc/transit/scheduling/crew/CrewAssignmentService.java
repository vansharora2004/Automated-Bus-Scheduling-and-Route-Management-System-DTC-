package com.dtc.transit.scheduling.crew;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.scheduling.engine.assignment.MrvCrewAssigner;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Runs crew assignment for a schedule that has just been built, and stores the result.
 *
 * <p>Separate from the engine run on purpose. Blocks and duties depend only on the timetable and the fleet; crew
 * assignment depends on who is available, which changes daily. Keeping them apart means a schedule can be
 * re-crewed after someone calls in sick without rebuilding a single block.
 *
 * <p>{@code MANDATORY}: this writes part of a schedule, so it must join the run's transaction rather than commit
 * a roster for a schedule that then fails to be written.
 */
@Service
public class CrewAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(CrewAssignmentService.class);

    private static final int BATCH = 500;

    private final CrewSnapshotLoader crewLoader;
    private final JdbcTemplate jdbc;
    private final AppTimeProperties timeProperties;

    public CrewAssignmentService(
            CrewSnapshotLoader crewLoader, JdbcTemplate jdbc, AppTimeProperties timeProperties) {
        this.crewLoader = crewLoader;
        this.jdbc = jdbc;
        this.timeProperties = timeProperties;
    }

    /**
     * Assigns crew to a persisted schedule's duties.
     *
     * <p>Reads the duties back from the database rather than taking them from the engine result, because the
     * assignments have to reference duty ids that only exist once the duties are written.
     *
     * @return what was booked and what could not be
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result assign(Long scheduleId, Long depotId, LocalDate serviceDate, RuleSet rules) {
        List<StoredDuty> storedDuties = jdbc.query(
                """
                SELECT id, duty_no, mode, duty_type, sign_on_sec, sign_off_sec, platform_sec, paid_sec,
                       break_sec, spread_sec, overtime_sec
                FROM duty WHERE schedule_id = ? ORDER BY duty_no
                """,
                (rs, row) -> new StoredDuty(
                        rs.getLong("id"),
                        new DutyPlan(
                                rs.getInt("duty_no"),
                                com.dtc.transit.scheduling.engine.model.SchedulingMode.valueOf(
                                        rs.getString("mode")),
                                com.dtc.transit.scheduling.engine.model.DutyType.valueOf(
                                        rs.getString("duty_type")),
                                rs.getInt("sign_on_sec"),
                                rs.getInt("sign_off_sec"),
                                rs.getInt("platform_sec"),
                                rs.getInt("paid_sec"),
                                rs.getInt("break_sec"),
                                rs.getInt("spread_sec"),
                                rs.getInt("overtime_sec"),
                                // Pieces are not needed for assignment: eligibility and fairness depend on the
                                // duty's times and type, not on which bus it came from.
                                List.of(new com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan(
                                        0,
                                        0,
                                        0,
                                        rs.getInt("sign_on_sec"),
                                        rs.getInt("sign_off_sec"),
                                        null,
                                        null)),
                                List.of())),
                scheduleId);

        if (storedDuties.isEmpty()) {
            return new Result(0, List.of());
        }

        var crew = crewLoader.load(depotId, serviceDate);
        var assigner = new MrvCrewAssigner(timeProperties.zone());
        var roster = assigner.assign(
                storedDuties.stream().map(StoredDuty::plan).toList(),
                crew.pool(),
                crew.history(),
                depotId,
                serviceDate,
                Map.of(),
                rules);

        Map<Integer, Long> dutyIdByNo = new java.util.HashMap<>();
        storedDuties.forEach(duty -> dutyIdByNo.put(duty.plan().dutyNo(), duty.id()));

        List<Object[]> rows = new ArrayList<>(roster.bookings().size());
        for (var booking : roster.bookings()) {
            Long dutyId = dutyIdByNo.get(booking.dutyNo());
            if (dutyId == null) {
                continue;
            }
            rows.add(new Object[] {
                dutyId,
                booking.crewRole(),
                booking.crewMemberId(),
                serviceDate,
                Timestamp.from(booking.startsAt()),
                Timestamp.from(booking.endsAt()),
                "DRAFT"
            });
        }
        for (int start = 0; start < rows.size(); start += BATCH) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO duty_assignment (duty_id, crew_role, crew_member_id, service_date, starts_at,
                                                 ends_at, schedule_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    rows.subList(start, Math.min(start + BATCH, rows.size())));
        }

        // Unassigned duties become conflicts carrying the reason histogram, which is what makes them actionable.
        List<Object[]> conflictRows = roster.unassigned().stream()
                .map(unassigned -> new Object[] {
                    scheduleId,
                    com.dtc.transit.scheduling.engine.vehicle.ConflictTypes.UNASSIGNED_DUTY,
                    "HARD",
                    histogramJson(unassigned.histogram()),
                    "Duty %d has no eligible %s. %s"
                            .formatted(unassigned.dutyNo(), unassigned.crewRole(), unassigned.description())
                })
                .toList();
        if (!conflictRows.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO conflict (schedule_id, type, severity, details, message)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    conflictRows);
        }

        log.info(
                "assigned crew for schedule {}: {} booked, {} unassigned, from a pool of {}",
                scheduleId,
                rows.size(),
                roster.unassigned().size(),
                crew.pool().size());

        return new Result(rows.size(), roster.unassigned());
    }

    /**
     * The rejection histogram as JSON.
     *
     * <p>Stored alongside the message so a report can aggregate across duties — "most unstaffable duties failed
     * on rest" is a different and more useful finding than twenty individual messages.
     */
    private static String histogramJson(
            Map<com.dtc.transit.scheduling.engine.model.RejectionReason, Integer> histogram) {
        if (histogram.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : new java.util.TreeMap<>(histogram).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private record StoredDuty(Long id, DutyPlan plan) {}

    /** @param unassigned every duty nobody could legally take, each with its reason histogram */
    public record Result(int assignedCount, List<MrvCrewAssigner.Unassigned> unassigned) {}
}
