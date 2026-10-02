package com.dtc.transit.scheduling.engine.model;

import java.util.Comparator;
import java.util.List;

/**
 * The crew available to a depot on a service date.
 *
 * <p>Sorted by id. Assignment picks the best-scoring candidate and the tiebreak has to be stable, or two runs
 * over the same input would roster different people.
 */
public record CrewPool(List<CrewView> members) {

    public CrewPool {
        members = members.stream().sorted(Comparator.comparingLong(CrewView::id)).toList();
    }

    public List<CrewView> ofRole(String crewRole) {
        return members.stream()
                .filter(member -> crewRole.equals(member.crewRole()))
                .toList();
    }

    public int size() {
        return members.size();
    }
}
