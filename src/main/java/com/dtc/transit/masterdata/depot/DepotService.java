package com.dtc.transit.masterdata.depot;

import java.util.Set;

import org.locationtech.jts.geom.Point;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.common.filtering.Filters;
import com.dtc.transit.common.geo.GeoSupport;
import com.dtc.transit.common.paging.SortWhitelist;
import com.dtc.transit.security.DepotAccessEvaluator;

/**
 * Depot administration.
 *
 * <p>Depots are readable by everyone: a scheduler needs to see the depot they belong to, and planners
 * need the network. Only an administrator may create or change one.
 */
@Service
public class DepotService {

    /** Sortable properties. Anything outside this set is rejected rather than silently ignored. */
    public static final Set<String> SORTABLE = Set.of("code", "name", "chargingBays", "parkingCapacity");

    private final DepotRepository depots;
    private final SortWhitelist sortWhitelist;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;

    public DepotService(
            DepotRepository depots,
            SortWhitelist sortWhitelist,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events) {
        this.depots = depots;
        this.sortWhitelist = sortWhitelist;
        this.depotAccess = depotAccess;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<Depot> search(String q, Boolean active, Pageable pageable) {
        var spec = org.springframework.data.jpa.domain.Specification.allOf(
                Filters.<Depot>eq("active", active), freeText(q), scopeToOwnDepot());
        return depots.findAll(spec, sortWhitelist.apply(pageable, SORTABLE));
    }

    @Transactional(readOnly = true)
    public Depot get(Long id) {
        Depot depot = depots.findById(id).orElseThrow(() -> NotFoundException.of("Depot", id));
        // A depot-bound caller may read only their own depot, and an out-of-scope read reads as absent.
        depotAccess.requireAccess(depot.getId(), "Depot", id);
        return depot;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Depot create(
            String code, String name, double longitude, double latitude, Integer parkingCapacity, int chargingBays) {
        if (depots.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("DEPOT_CODE_TAKEN", "Depot code '" + code + "' already exists");
        }
        Point location = GeoSupport.point(longitude, latitude);
        var depot = depots.save(new Depot(code, name, location, parkingCapacity, chargingBays));
        events.publishEvent(AuditEvent.created("DEPOT", depot.getId(), describe(depot)));
        return depot;
    }

    /**
     * A depot-bound caller sees only their own depot in the list.
     *
     * <p>Narrowing the query rather than filtering results afterwards: post-filtering would return a
     * page of 20 containing one row, with a total describing rows the caller cannot see.
     */
    private org.springframework.data.jpa.domain.Specification<Depot> scopeToOwnDepot() {
        return depotAccess
                .currentDepotId()
                .<org.springframework.data.jpa.domain.Specification<Depot>>map(
                        depotId -> (root, query, builder) -> builder.equal(root.get("id"), depotId))
                .orElse(null);
    }

    private static org.springframework.data.jpa.domain.Specification<Depot> freeText(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        return (root, query, builder) -> builder.or(
                Filters.<Depot>contains("code", term).toPredicate(root, query, builder),
                Filters.<Depot>contains("name", term).toPredicate(root, query, builder));
    }

    private static String describe(Depot depot) {
        return """
                {"code":"%s","name":"%s","chargingBays":%d,"active":%s}"""
                .formatted(depot.getCode(), depot.getName(), depot.getChargingBays(), depot.isActive());
    }
}
