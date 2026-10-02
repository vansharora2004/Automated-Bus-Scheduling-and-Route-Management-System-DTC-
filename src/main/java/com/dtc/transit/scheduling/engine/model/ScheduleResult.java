package com.dtc.transit.scheduling.engine.model;

import java.util.List;

/**
 * Everything one run of the engine produced.
 *
 * <p>One object rather than several out-parameters, so the persister writes a consistent whole in one
 * transaction. A partially written schedule looks runnable and is not.
 *
 * @param duties empty until Phase 7, which cuts blocks into crew duties
 * @param handovers empty until Phase 7
 */
public record ScheduleResult(
        VehicleSchedule vehicleSchedule,
        List<BusAssignmentPlan> busAssignments,
        List<DutyPlan> duties,
        List<HandoverPlan> handovers,
        List<EngineConflict> conflicts,
        ScheduleMetrics metrics) {

    public ScheduleResult {
        busAssignments = List.copyOf(busAssignments);
        duties = List.copyOf(duties);
        handovers = List.copyOf(handovers);
        conflicts = List.copyOf(conflicts);
    }

    /** Whether anything blocks publication. */
    public boolean hasBlockingConflicts() {
        return conflicts.stream().anyMatch(EngineConflict::isBlocking);
    }

    public List<EngineConflict> blockingConflicts() {
        return conflicts.stream().filter(EngineConflict::isBlocking).toList();
    }
}
