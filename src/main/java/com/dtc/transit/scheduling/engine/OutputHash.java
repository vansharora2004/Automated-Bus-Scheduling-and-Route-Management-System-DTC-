package com.dtc.transit.scheduling.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/**
 * A digest of what a run produced.
 *
 * <p>Determinism is the property every later phase leans on: the same input and the same seed must produce the
 * same schedule, or a performance comparison between two runs means nothing and a regression cannot be
 * reproduced. Comparing two runs row by row is possible and nobody does it; comparing one hash is something a
 * test can assert in a line.
 *
 * <p>Covers the logical content only. Database ids, timestamps and elapsed times differ between runs for reasons
 * that have nothing to do with the schedule, and including them would make the hash change every time and get
 * the check deleted.
 */
public final class OutputHash {

    private OutputHash() {
        // static helper
    }

    public static String of(
            VehicleSchedule schedule, List<DutyPlan> duties, List<BusAssignmentPlan> assignments) {

        MessageDigest digest = sha256();

        for (Block block : schedule.blocks()) {
            append(
                    digest,
                    "block",
                    block.blockNo(),
                    block.vehicleClass(),
                    block.pullOutSec(),
                    block.pullInSec());
            for (BlockEvent event : block.events()) {
                append(
                        digest,
                        "event",
                        block.blockNo(),
                        event.seq(),
                        event.type(),
                        event.tripId(),
                        event.startSec(),
                        event.endSec(),
                        event.reliefOpportunity());
            }
        }
        schedule.uncovered()
                .forEach(uncovered -> append(digest, "uncovered", uncovered.tripId(), uncovered.reason()));

        for (DutyPlan duty : duties) {
            append(
                    digest,
                    "duty",
                    duty.dutyNo(),
                    duty.mode(),
                    duty.dutyType(),
                    duty.signOnSec(),
                    duty.signOffSec(),
                    duty.platformSec(),
                    duty.paidSec(),
                    duty.breakSec());
            for (PieceOfWorkPlan piece : duty.pieces()) {
                append(
                        digest,
                        "piece",
                        duty.dutyNo(),
                        piece.blockNo(),
                        piece.fromEventSeq(),
                        piece.toEventSeq(),
                        piece.startSec(),
                        piece.endSec());
            }
        }

        assignments.forEach(plan -> append(digest, "bus", plan.blockNo(), plan.busId(), plan.fromSec()));

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void append(MessageDigest digest, Object... parts) {
        StringBuilder line = new StringBuilder();
        for (Object part : parts) {
            line.append(part).append('|');
        }
        line.append('\n');
        digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
