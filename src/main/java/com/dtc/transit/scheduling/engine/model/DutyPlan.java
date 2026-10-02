package com.dtc.transit.scheduling.engine.model;

import java.util.List;

/**
 * One person's working day, as the engine plans it.
 *
 * <p>Named crew come later: this is the shape of the work, not who does it. Separating the two is what lets a
 * duty be re-crewed when someone calls in sick without rebuilding the schedule.
 *
 * @param platformSec time actually spent on a bus, driving or standing with it
 * @param paidSec what the crew is paid for, which includes sign-on, sign-off and paid breaks, and is never
 *     below the minimum paid guarantee
 * @param breakSec unpaid break time inside the duty
 * @param spreadSec sign-on to sign-off, the figure the spread-over rule limits
 * @param overtimeSec work beyond the normal maximum, which is zero unless overtime is permitted
 */
public record DutyPlan(
        int dutyNo,
        SchedulingMode mode,
        DutyType dutyType,
        int signOnSec,
        int signOffSec,
        int platformSec,
        int paidSec,
        int breakSec,
        int spreadSec,
        int overtimeSec,
        List<PieceOfWorkPlan> pieces,
        List<EngineConflict> conflicts) {

    public DutyPlan {
        pieces = List.copyOf(pieces);
        conflicts = List.copyOf(conflicts);
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("duty " + dutyNo + " has no work in it");
        }
        if (signOffSec <= signOnSec) {
            throw new IllegalArgumentException("duty " + dutyNo + " signs off at or before it signs on");
        }
    }

    /** The blocks this duty touches. One in linked mode; possibly several once Phase 8 allows bus changes. */
    public List<Integer> blockNos() {
        return pieces.stream().map(PieceOfWorkPlan::blockNo).distinct().sorted().toList();
    }

    public DutyPlan withDutyNo(int newDutyNo) {
        return new DutyPlan(
                newDutyNo,
                mode,
                dutyType,
                signOnSec,
                signOffSec,
                platformSec,
                paidSec,
                breakSec,
                spreadSec,
                overtimeSec,
                pieces,
                conflicts);
    }
}
