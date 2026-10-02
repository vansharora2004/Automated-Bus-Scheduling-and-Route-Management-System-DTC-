package com.dtc.transit.scheduling.engine.model;

import java.util.List;

/**
 * Something wrong with a schedule, as the engine reports it.
 *
 * <p>The engine never throws for a schedule it cannot make legal. A thrown exception loses every other
 * finding, and a scheduler needs the whole list to decide what to fix first.
 *
 * @param type a code from the conflict catalogue, for example {@code UNCOVERED_TRIP}
 * @param message written for the person who has to resolve it, not for a log parser
 */
public record EngineConflict(String type, Severity severity, String message, List<EntityRef> refs) {

    public EngineConflict {
        refs = refs == null ? List.of() : List.copyOf(refs);
    }

    public static EngineConflict hard(String type, String message, List<EntityRef> refs) {
        return new EngineConflict(type, Severity.HARD, message, refs);
    }

    public static EngineConflict soft(String type, String message, List<EntityRef> refs) {
        return new EngineConflict(type, Severity.SOFT, message, refs);
    }

    public boolean isBlocking() {
        return severity == Severity.HARD;
    }
}
