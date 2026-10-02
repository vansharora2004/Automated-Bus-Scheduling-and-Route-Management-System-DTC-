package com.dtc.transit.masterdata.bus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.jpa.BaseEntity;
import com.dtc.transit.masterdata.depot.Depot;

/** A vehicle in the fleet. */
@Entity
@Table(name = "bus")
public class Bus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bus_seq")
    @SequenceGenerator(name = "bus_seq", sequenceName = "bus_seq", allocationSize = 50)
    private Long id;

    /** Canonical form, which the unique index is built on. */
    @Column(name = "registration_no", nullable = false)
    private String registrationNo;

    /** As entered, for display. */
    @Column(name = "registration_no_raw", nullable = false)
    private String registrationNoRaw;

    @Column(name = "fleet_no", nullable = false)
    private String fleetNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depot_id", nullable = false)
    private Depot depot;

    @Enumerated(EnumType.STRING)
    @Column(name = "bus_type", nullable = false)
    private BusType busType;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", nullable = false)
    private FuelType fuelType;

    @Column(name = "is_ac", nullable = false)
    private boolean airConditioned;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "ev_range_km")
    private Integer evRangeKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BusStatus status = BusStatus.ACTIVE;

    protected Bus() {
        // for JPA
    }

    public Bus(
            String registrationNoRaw,
            String fleetNo,
            Depot depot,
            BusType busType,
            FuelType fuelType,
            boolean airConditioned,
            int capacity,
            Integer evRangeKm) {
        this.registrationNoRaw = registrationNoRaw;
        this.registrationNo = RegistrationNo.normalise(registrationNoRaw);
        this.fleetNo = fleetNo;
        this.depot = depot;
        this.busType = busType;
        this.fuelType = fuelType;
        this.airConditioned = airConditioned;
        this.capacity = capacity;
        this.evRangeKm = evRangeKm;
        requireRangeForElectric();
    }

    /**
     * An electric bus must declare its range.
     *
     * <p>Checked here as well as by a database constraint, so the caller gets an explanatory 422 rather
     * than a constraint-violation 500. Block building in Phase 6 cannot schedule the bus without it.
     */
    private void requireRangeForElectric() {
        if (fuelType.requiresRange() && evRangeKm == null) {
            throw new BusinessRuleException(
                    "EV_RANGE_REQUIRED", "An electric bus must declare ev_range_km so blocks can respect its range");
        }
    }

    public Long getId() {
        return id;
    }

    public String getRegistrationNo() {
        return registrationNo;
    }

    public String getRegistrationNoRaw() {
        return registrationNoRaw;
    }

    public String getFleetNo() {
        return fleetNo;
    }

    public Depot getDepot() {
        return depot;
    }

    public BusType getBusType() {
        return busType;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public boolean isAirConditioned() {
        return airConditioned;
    }

    public int getCapacity() {
        return capacity;
    }

    public Integer getEvRangeKm() {
        return evRangeKm;
    }

    public BusStatus getStatus() {
        return status;
    }

    public void changeStatus(BusStatus status) {
        this.status = status;
    }

    public void transferTo(Depot depot) {
        this.depot = depot;
    }
}
