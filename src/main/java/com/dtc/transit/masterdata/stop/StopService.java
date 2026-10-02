package com.dtc.transit.masterdata.stop;

import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.common.geo.GeoSupport;
import com.dtc.transit.common.paging.SortWhitelist;

/**
 * Stop administration.
 *
 * <p>Stops are network-wide rather than depot-owned, so there is no depot scope here. Routes from
 * several depots share the same stops, and hiding them per depot would make route planning impossible.
 */
@Service
public class StopService {

    public static final Set<String> SORTABLE = Set.of("code", "name", "terminal", "reliefPoint");

    private final StopRepository stops;
    private final SortWhitelist sortWhitelist;
    private final ApplicationEventPublisher events;

    public StopService(StopRepository stops, SortWhitelist sortWhitelist, ApplicationEventPublisher events) {
        this.stops = stops;
        this.sortWhitelist = sortWhitelist;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<Stop> search(StopFilter filter, Pageable pageable) {
        return stops.findAll(StopSpecifications.of(filter), sortWhitelist.apply(pageable, SORTABLE));
    }

    @Transactional(readOnly = true)
    public Stop get(Long id) {
        return stops.findById(id).orElseThrow(() -> NotFoundException.of("Stop", id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Stop create(NewStop command) {
        if (stops.existsByCodeIgnoreCase(command.code())) {
            throw new ConflictException("STOP_CODE_TAKEN", "Stop code '" + command.code() + "' already exists");
        }
        var stop = new Stop(
                command.code(), command.name(), GeoSupport.point(command.longitude(), command.latitude()));
        stop.setFlags(command.terminal(), command.reliefPoint(), command.crewFacilities());
        stops.save(stop);
        events.publishEvent(AuditEvent.created("STOP", stop.getId(), describe(stop)));
        return stop;
    }

    private static String describe(Stop stop) {
        return """
                {"code":"%s","name":"%s","terminal":%s,"reliefPoint":%s}"""
                .formatted(stop.getCode(), stop.getName(), stop.isTerminal(), stop.isReliefPoint());
    }

    /** Creation command, kept separate from the HTTP request body. */
    public record NewStop(
            String code,
            String name,
            double longitude,
            double latitude,
            boolean terminal,
            boolean reliefPoint,
            boolean crewFacilities) {}
}
