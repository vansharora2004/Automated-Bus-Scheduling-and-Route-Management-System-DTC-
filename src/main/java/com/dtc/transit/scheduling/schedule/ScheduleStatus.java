package com.dtc.transit.scheduling.schedule;

import java.util.Set;

import com.dtc.transit.common.error.BusinessRuleException;

/**
 * The life of a schedule version.
 *
 * <pre>
 * DRAFT --validate--> VALIDATED --publish--> PUBLISHED --newer version--> SUPERSEDED
 *   |                     |
 *   |                     +--any edit--> DRAFT
 *   +--discard--> DISCARDED
 * </pre>
 *
 * <p>A published schedule is immutable. Crews have been told where to be, so correcting it means publishing a
 * later version rather than rewriting history under them.
 */
public enum ScheduleStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    SUPERSEDED,
    DISCARDED;

    /** Whether blocks, duties and assignments may still change. */
    public boolean isEditable() {
        return this == DRAFT || this == VALIDATED;
    }

    public boolean isLive() {
        return this == PUBLISHED;
    }

    private Set<ScheduleStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(VALIDATED, DISCARDED);
            // An edit sends a validated schedule back to draft, because the validation no longer describes it.
            case VALIDATED -> Set.of(PUBLISHED, DRAFT, DISCARDED);
            case PUBLISHED -> Set.of(SUPERSEDED);
            case SUPERSEDED, DISCARDED -> Set.of();
        };
    }

    /** Refuses a transition the lifecycle does not allow, naming both states. */
    public void requireCanMoveTo(ScheduleStatus next) {
        if (!allowedNext().contains(next)) {
            throw new BusinessRuleException(
                    "ILLEGAL_SCHEDULE_TRANSITION",
                    "A schedule cannot go from %s to %s".formatted(this, next));
        }
    }
}
