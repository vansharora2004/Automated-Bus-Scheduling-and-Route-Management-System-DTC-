package com.dtc.transit.masterdata.bus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.common.paging.SortWhitelist;
import com.dtc.transit.masterdata.depot.Depot;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.security.DepotAccessEvaluator;
import com.dtc.transit.security.DepotScope;

/** Fleet administration. */
@Service
public class BusService {

    public static final Set<String> SORTABLE =
            Set.of("registrationNo", "fleetNo", "status", "capacity", "busType", "fuelType", "depot.code");

    private final BusRepository buses;
    private final BusUnavailabilityRepository unavailability;
    private final DepotRepository depots;
    private final SortWhitelist sortWhitelist;
    private final DepotScope depotScope;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;

    public BusService(
            BusRepository buses,
            BusUnavailabilityRepository unavailability,
            DepotRepository depots,
            SortWhitelist sortWhitelist,
            DepotScope depotScope,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events) {
        this.buses = buses;
        this.unavailability = unavailability;
        this.depots = depots;
        this.sortWhitelist = sortWhitelist;
        this.depotScope = depotScope;
        this.depotAccess = depotAccess;
        this.events = events;
    }

    /**
     * Lists buses, narrowed to the caller's depot where they have one.
     *
     * <p>The depot scope is applied as a predicate on top of any requested {@code depotId}, so a
     * depot-bound caller asking for another depot gets an empty page rather than someone else's fleet
     * (edge case EC-SEC-02).
     */
    @Transactional(readOnly = true)
    public Page<Bus> search(BusFilter filter, Pageable pageable) {
        Specification<Bus> spec =
                Specification.allOf(BusSpecifications.of(filter), depotScope.restrict("depot.id"));
        return buses.findAll(spec, sortWhitelist.apply(pageable, SORTABLE));
    }

    @Transactional(readOnly = true)
    public Bus get(Long id) {
        Bus bus = buses.findById(id).orElseThrow(() -> NotFoundException.of("Bus", id));
        // Another depot's bus reads as absent, not as forbidden, so ids cannot be probed
        // (edge case EC-SEC-01).
        depotAccess.requireAccess(bus.getDepot().getId(), "Bus", id);
        return bus;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Bus create(NewBus command) {
        Depot depot = depots.findById(command.depotId())
                .orElseThrow(() -> NotFoundException.of("Depot", command.depotId()));
        depotAccess.requireAccess(depot.getId(), "Depot", command.depotId());

        String normalised = RegistrationNo.normalise(command.registrationNo());
        if (buses.existsByRegistrationNo(normalised)) {
            throw new ConflictException(
                    "REGISTRATION_TAKEN",
                    "A bus with registration '" + normalised + "' already exists (normalised from '"
                            + command.registrationNo() + "')");
        }
        if (buses.existsByDepotAndFleetNo(depot.getId(), command.fleetNo())) {
            throw new ConflictException(
                    "FLEET_NO_TAKEN",
                    "Fleet number '" + command.fleetNo() + "' is already used at depot " + depot.getCode());
        }

        var bus = buses.save(new Bus(
                command.registrationNo(),
                command.fleetNo(),
                depot,
                command.busType(),
                command.fuelType(),
                command.airConditioned(),
                command.capacity(),
                command.evRangeKm()));
        events.publishEvent(AuditEvent.created("BUS", bus.getId(), describe(bus)));
        return bus;
    }

    /**
     * Changes status, optionally recording the window the bus is out for.
     *
     * <p>A scheduler may do this for their own depot: a breakdown is discovered at the depot and has to
     * be recorded immediately, not escalated.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public Bus changeStatus(
            Long id, BusStatus status, Instant unavailableFrom, Instant unavailableTo, String note) {
        Bus bus = buses.findById(id).orElseThrow(() -> NotFoundException.of("Bus", id));
        depotAccess.requireAccess(bus.getDepot().getId(), "Bus", id);

        String before = describe(bus);
        bus.changeStatus(status);
        buses.save(bus);

        if (unavailableFrom != null && unavailableTo != null) {
            unavailability.save(new BusUnavailability(
                    bus.getId(), unavailableFrom, unavailableTo, reasonFor(status), note));
        }

        events.publishEvent(AuditEvent.updated("BUS", id, before, describe(bus)));
        return bus;
    }

    @Transactional(readOnly = true)
    public List<BusUnavailability> unavailabilityFor(Long busId) {
        get(busId);
        return unavailability.findByBusIdOrderByStartsAtAsc(busId);
    }

    private static UnavailabilityReason reasonFor(BusStatus status) {
        return switch (status) {
            case UNDER_MAINTENANCE -> UnavailabilityReason.MAINTENANCE;
            case BREAKDOWN -> UnavailabilityReason.BREAKDOWN;
            default -> UnavailabilityReason.OTHER;
        };
    }

    private static String describe(Bus bus) {
        return """
                {"registrationNo":"%s","fleetNo":"%s","depotId":%d,"status":"%s","fuelType":"%s"}"""
                .formatted(
                        bus.getRegistrationNo(),
                        bus.getFleetNo(),
                        bus.getDepot().getId(),
                        bus.getStatus(),
                        bus.getFuelType());
    }

    /** Creation command, kept separate from the HTTP request body. */
    public record NewBus(
            String registrationNo,
            String fleetNo,
            Long depotId,
            BusType busType,
            FuelType fuelType,
            boolean airConditioned,
            int capacity,
            Integer evRangeKm) {}
}
