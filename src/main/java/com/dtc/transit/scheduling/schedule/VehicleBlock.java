package com.dtc.transit.scheduling.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A block as stored: the summary row that the events hang off.
 *
 * <p>Read through JPA and written through JDBC batches. A run inserts thousands of these and a hundred thousand
 * events, which is a bulk load rather than a domain operation.
 */
@Entity
@Table(name = "vehicle_block")
public class VehicleBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vehicle_block_seq")
    @SequenceGenerator(name = "vehicle_block_seq", sequenceName = "vehicle_block_seq", allocationSize = 50)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "block_no", nullable = false)
    private int blockNo;

    @Column(name = "vehicle_class")
    private String vehicleClass;

    @Column(name = "pull_out_sec", nullable = false)
    private int pullOutSec;

    @Column(name = "pull_in_sec", nullable = false)
    private int pullInSec;

    @Column(name = "service_km", nullable = false)
    private double serviceKm;

    @Column(name = "dead_km", nullable = false)
    private double deadKm;

    protected VehicleBlock() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public int getBlockNo() {
        return blockNo;
    }

    public String getVehicleClass() {
        return vehicleClass;
    }

    public int getPullOutSec() {
        return pullOutSec;
    }

    public int getPullInSec() {
        return pullInSec;
    }

    public double getServiceKm() {
        return serviceKm;
    }

    public double getDeadKm() {
        return deadKm;
    }

    public double getTotalKm() {
        return serviceKm + deadKm;
    }
}
