package com.dtc.transit.scheduling.engine.constraint;

import java.util.ArrayList;
import java.util.List;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.duty.DutyCandidate;
import com.dtc.transit.scheduling.engine.duty.ReliefTransferTimes;
import com.dtc.transit.scheduling.engine.model.Severity;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * Whether a crew can physically get from one piece of work to the next.
 *
 * <p>Only reachable in unlinked mode, where a duty is assembled from pieces of several buses. The gap between
 * two consecutive pieces has to cover the journey between their relief points plus the handover buffer; anything
 * less is a duty that asks someone to be in two places at once.
 *
 * <p>Hard, and necessarily so. Every other unlinked rule is about how much someone works; this one is about
 * whether the duty is possible at all, and a schedule that fails it is not merely inefficient.
 */
public class HandoverConstraint implements Constraint<DutyCandidate> {

    private final ReliefTransferTimes transferTimes;

    public HandoverConstraint(ReliefTransferTimes transferTimes) {
        this.transferTimes = transferTimes;
    }

    @Override
    public String code() {
        return ConflictTypes.HANDOVER_INFEASIBLE;
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Scope scope() {
        return Scope.DUTY;
    }

    @Override
    public List<Violation> check(DutyCandidate duty, ValidationContext context) {
        List<DutyCandidate.WorkSegment> segments = duty.segments();
        if (segments.size() < 2) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (int i = 1; i < segments.size(); i++) {
            var previous = segments.get(i - 1);
            var next = segments.get(i);

            int available = next.startSec() - previous.endSec();
            int required = transferTimes.requiredGapSec(
                    previous.endReliefStopId(), next.startReliefStopId(), previous.endSec());

            if (available < required) {
                violations.add(new Violation(
                        code(),
                        Severity.HARD,
                        ("Duty leaves %d min to get from the end of block %d at %s to the start of block %d, "
                                        + "which needs %d min including the handover buffer")
                                .formatted(
                                        available / 60,
                                        previous.blockNo(),
                                        ServiceTime.format(previous.endSec()),
                                        next.blockNo(),
                                        required / 60),
                        available,
                        required,
                        List.of()));
            }
        }
        return violations;
    }
}
