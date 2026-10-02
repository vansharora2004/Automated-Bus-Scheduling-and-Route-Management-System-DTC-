package com.dtc.transit.scheduling.engine.constraint;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.Severity;

/**
 * One constraint failing on one subject.
 *
 * <p>Carries the numbers, not just a message. "Continuous work exceeded" sends a planner looking; "315 minutes
 * of continuous work against a limit of 300" tells them how much slack they need to find.
 *
 * @param actual the measured value, in the constraint's own units
 * @param limit the value it was measured against
 */
public record Violation(
        String code, Severity severity, String message, long actual, long limit, List<EntityRef> refs) {

    public Violation {
        refs = refs == null ? List.of() : List.copyOf(refs);
    }

    public static Violation hard(String code, String message, long actual, long limit) {
        return new Violation(code, Severity.HARD, message, actual, limit, List.of());
    }

    public static Violation soft(String code, String message, long actual, long limit) {
        return new Violation(code, Severity.SOFT, message, actual, limit, List.of());
    }

    /** The conflict row this violation becomes once a schedule is persisted. */
    public EngineConflict toConflict() {
        return new EngineConflict(code, severity, message, refs);
    }

    public boolean isBlocking() {
        return severity == Severity.HARD;
    }
}
