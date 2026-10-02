package com.dtc.transit.scheduling.engine.duty;

import com.dtc.transit.scheduling.engine.model.DutyType;

/**
 * Everything measurable about a duty, computed once.
 *
 * <p>Definitions matter here more than code, because these numbers decide what a crew is paid and whether the
 * duty is legal. They are:
 *
 * <pre>
 * signOnSec    when the crew signs on: the bus is taken over at signOnSec + rules.signOnMin
 * signOffSec   when the crew signs off: the bus is handed over at signOffSec - rules.signOffMin
 * platformSec  time with the bus, excluding unpaid breaks
 * breakSec     unpaid breaks: idle periods long enough to qualify under minBreakMin
 * workSec      sign-on + platform + sign-off; this is what maxWorkPerDuty limits
 * spreadSec    signOffSec - signOnSec, including breaks; what maxSpreadOver limits
 * paidSec      workSec, floored at the minimum paid guarantee
 * longestStretchSec  the longest run of work with no qualifying break; what maxContinuousWork limits
 * overtimeSec  work beyond the normal maximum, zero unless overtime is permitted
 * </pre>
 *
 * <p>{@code workSec} and {@code spreadSec} differ by exactly the break time, which is the distinction the two
 * rules exist to make: a crew can be at work for twelve hours while working eight of them.
 */
public record DutyMetrics(
        int signOnSec,
        int signOffSec,
        int platformSec,
        int breakSec,
        int workSec,
        int spreadSec,
        int paidSec,
        int longestStretchSec,
        int overtimeSec,
        DutyType dutyType) {

    public DutyMetrics {
        if (signOffSec <= signOnSec) {
            throw new IllegalArgumentException("a duty cannot sign off at or before it signs on");
        }
    }

    /** Whether the minimum paid guarantee had to top this duty up, which is what makes it a short duty. */
    public boolean isShortDuty() {
        return paidSec > workSec;
    }
}
