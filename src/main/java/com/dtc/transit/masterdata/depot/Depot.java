package com.dtc.transit.masterdata.depot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.Point;

import com.dtc.transit.common.jpa.BaseEntity;

/**
 * A depot: where buses are parked and maintained and where crew report.
 *
 * <p>Depots are the unit of scheduling and of access control. A run covers one depot for one service
 * date, and a depot-bound user sees only their own.
 */
@Entity
@Table(name = "depot")
public class Depot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "depot_seq")
    @SequenceGenerator(name = "depot_seq", sequenceName = "depot_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "parking_capacity")
    private Integer parkingCapacity;

    /** Charging points available, which caps how many electric buses can charge at once. */
    @Column(name = "charging_bays", nullable = false)
    private int chargingBays;

    @Column(nullable = false)
    private boolean active = true;

    protected Depot() {
        // for JPA
    }

    public Depot(String code, String name, Point location, Integer parkingCapacity, int chargingBays) {
        this.code = code;
        this.name = name;
        this.location = location;
        this.parkingCapacity = parkingCapacity;
        this.chargingBays = chargingBays;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Point getLocation() {
        return location;
    }

    public Integer getParkingCapacity() {
        return parkingCapacity;
    }

    public int getChargingBays() {
        return chargingBays;
    }

    public boolean isActive() {
        return active;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void relocate(Point location) {
        this.location = location;
    }

    public void setCapacity(Integer parkingCapacity, int chargingBays) {
        this.parkingCapacity = parkingCapacity;
        this.chargingBays = chargingBays;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
