package com.dtc.transit.scheduling.engine.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * One crew member, as the engine sees them.
 *
 * @param crewRole DRIVER or CONDUCTOR; a person fills one role, not both
 * @param licenceExpiry null for a conductor, who needs none; never null for a driver, which the database
 *     enforces at entry so the engine never has to guess
 * @param weeklyOffDow ISO day of week, 1 = Monday, or null when the person has no fixed day off
 * @param qualifications codes such as EV, which some duties require
 * @param leave windows in service-day seconds, already clamped to this service date
 */
public record CrewView(
        long id,
        String employeeCode,
        String crewRole,
        long depotId,
        String licenceClass,
        LocalDate licenceExpiry,
        Integer weeklyOffDow,
        String status,
        Set<String> qualifications,
        List<TimeWindow> leave) {

    public CrewView {
        qualifications = qualifications == null ? Set.of() : Set.copyOf(qualifications);
        leave = leave == null ? List.of() : List.copyOf(leave);
    }

    public boolean isDriver() {
        return "DRIVER".equals(crewRole);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** Whether the licence is valid on a service date. A licence expiring today is still valid today. */
    public boolean hasValidLicenceOn(LocalDate serviceDate) {
        return licenceExpiry == null || !licenceExpiry.isBefore(serviceDate);
    }

    /** Whether any approved leave overlaps a working window. */
    public boolean isOnLeaveDuring(TimeWindow window) {
        return leave.stream().anyMatch(window::overlaps);
    }
}
