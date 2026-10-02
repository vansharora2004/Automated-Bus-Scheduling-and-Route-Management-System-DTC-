package com.dtc.transit.scheduling.engine.assignment;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/** Puts physical buses on blocks. */
public interface BusAssigner {

    String name();

    Result assign(VehicleSchedule schedule, DepotContext context, RuleSet rules);

    /**
     * @param unassignedBlockNos blocks left without a bus, each of which also carries a hard conflict
     */
    record Result(
            List<BusAssignmentPlan> assignments, List<Integer> unassignedBlockNos, List<EngineConflict> conflicts) {

        public Result {
            assignments = List.copyOf(assignments);
            unassignedBlockNos = List.copyOf(unassignedBlockNos);
            conflicts = List.copyOf(conflicts);
        }
    }
}
