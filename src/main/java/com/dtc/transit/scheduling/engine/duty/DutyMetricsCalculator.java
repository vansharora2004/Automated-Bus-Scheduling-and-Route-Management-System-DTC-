package com.dtc.transit.scheduling.engine.duty;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Computes a duty's metrics from the work it covers.
 *
 * <p>One place, used by the builder while it searches and by the validator afterwards. Two implementations would
 * eventually disagree, and the one that decided legality would not be the one that decided pay.
 *
 * <p>The only judgement in here is what counts as a break: an idle period during which the crew is genuinely
 * released, long enough to qualify under {@code minBreakMin}. A layover of four minutes is not a break, it is
 * standing at a terminal waiting, and counting it as one would let a duty work twelve hours in half-hour
 * stretches without ever taking a rest.
 */
public final class DutyMetricsCalculator {

    private DutyMetricsCalculator() {
        // static calculator
    }

    /**
     * Metrics for a duty covering the given segments.
     *
     * @param segments in time order, each a contiguous stretch of one bus's work
     */
    public static DutyMetrics compute(List<DutyCandidate.WorkSegment> segments, RuleSet rules) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("cannot compute metrics for a duty with no work");
        }

        int firstBusTime = segments.get(0).startSec();
        int lastBusTime = segments.get(segments.size() - 1).endSec();

        // Sign-on is paid time before the crew takes the bus; sign-off is paid time after they hand it over.
        int signOnSec = Math.max(0, firstBusTime - rules.signOnSec());
        int signOffSec = lastBusTime + rules.signOffSec();

        int breakSec = 0;
        int platformSec = 0;

        // Sign-on counts as work with no break before it, so the first stretch starts here.
        int currentStretch = rules.signOnSec();
        int longestStretch = currentStretch;

        for (int s = 0; s < segments.size(); s++) {
            DutyCandidate.WorkSegment segment = segments.get(s);

            if (s > 0) {
                // The gap between two segments. In linked mode this is a mid-day depot park; in unlinked mode it
                // may be the crew travelling between buses. Either way the crew is released if it is long enough.
                int gap = segment.startSec() - segments.get(s - 1).endSec();
                if (gap >= rules.minBreakSec()) {
                    breakSec += gap;
                    currentStretch = 0;
                } else {
                    currentStretch += gap;
                    longestStretch = Math.max(longestStretch, currentStretch);
                }
            }

            for (BlockEvent event : segment.events()) {
                int duration = event.durationSec();
                if (event.type().isIdle() && duration >= rules.minBreakSec()) {
                    // A qualifying break: unpaid, and it resets the continuous-work clock.
                    breakSec += duration;
                    currentStretch = 0;
                } else {
                    platformSec += duration;
                    currentStretch += duration;
                    longestStretch = Math.max(longestStretch, currentStretch);
                }
            }
        }

        // Sign-off continues the final stretch rather than starting a new one.
        currentStretch += rules.signOffSec();
        longestStretch = Math.max(longestStretch, currentStretch);

        int workSec = rules.signOnSec() + platformSec + rules.signOffSec();
        int spreadSec = signOffSec - signOnSec;
        int paidSec = Math.max(workSec, rules.minPaidDutySec());
        int overtimeSec = Math.max(0, workSec - rules.maxWorkPerDutySec());

        return new DutyMetrics(
                signOnSec,
                signOffSec,
                platformSec,
                breakSec,
                workSec,
                spreadSec,
                paidSec,
                longestStretch,
                overtimeSec,
                classify(signOnSec, signOffSec, segments, rules));
    }

    /**
     * What kind of shift this is.
     *
     * <p>Derived rather than chosen, so the type always describes the duty it is attached to. Shape is checked
     * before time: a duty with a long unpaid gap in the middle is a split duty whatever hour it starts, because
     * that gap is the thing that makes it unpopular and the thing allowances are paid for.
     */
    private static DutyType classify(
            int signOnSec, int signOffSec, List<DutyCandidate.WorkSegment> segments, RuleSet rules) {

        for (int s = 1; s < segments.size(); s++) {
            if (segments.get(s).startSec() - segments.get(s - 1).endSec() >= rules.midDayDepotReturnGapSec()) {
                return DutyType.SPLIT;
            }
        }
        boolean splitInsideASegment = segments.stream()
                .flatMap(segment -> segment.events().stream())
                .anyMatch(event -> event.type().isIdle() && event.durationSec() >= rules.midDayDepotReturnGapSec());
        if (splitInsideASegment) {
            return DutyType.SPLIT;
        }

        if (signOnSec < NIGHT_END_SEC || signOffSec > NIGHT_START_SEC) {
            return DutyType.NIGHT;
        }
        if (signOnSec < EARLY_END_SEC) {
            return DutyType.EARLY;
        }
        if (signOffSec > LATE_START_SEC) {
            return DutyType.LATE;
        }
        return DutyType.MIDDLE;
    }

    /** Before 04:00 counts as night work, which attracts a night allowance. */
    private static final int NIGHT_END_SEC = 4 * 3600;

    /** Past midnight counts as night work too. */
    private static final int NIGHT_START_SEC = 24 * 3600;

    /** Signing on before 08:00 is an early turn. */
    private static final int EARLY_END_SEC = 8 * 3600;

    /** Signing off after 20:00 is a late turn. */
    private static final int LATE_START_SEC = 20 * 3600;
}
