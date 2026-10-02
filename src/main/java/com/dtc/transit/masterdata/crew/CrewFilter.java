package com.dtc.transit.masterdata.crew;

import java.time.LocalDate;

/**
 * Query parameters for the crew list.
 *
 * @param depotId               restrict to one depot
 * @param crewRole              driver or conductor
 * @param status                employment state
 * @param licenceExpiringBefore crew whose licence lapses before this date, for renewal chasing
 * @param availableOn           crew who can actually work on this date: active, licence valid, not on
 *     leave
 * @param qualification         a qualification code the crew member must hold
 * @param q                     free-text term matched against employee code and name
 */
public record CrewFilter(
        Long depotId,
        CrewRole crewRole,
        CrewStatus status,
        LocalDate licenceExpiringBefore,
        LocalDate availableOn,
        String qualification,
        String q) {}
