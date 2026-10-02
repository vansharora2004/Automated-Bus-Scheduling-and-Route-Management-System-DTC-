package com.dtc.transit.route.coverage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.MultiPolygon;

/** An area whose service coverage is measured, such as a ward. */
@Entity
@Table(name = "coverage_zone")
public class CoverageZone {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "coverage_zone_seq")
    @SequenceGenerator(name = "coverage_zone_seq", sequenceName = "coverage_zone_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "zone_type", nullable = false)
    private String zoneType;

    @Column(columnDefinition = "geometry(MultiPolygon,4326)", nullable = false)
    private MultiPolygon geom;

    /** Null where no figure is available, in which case coverage is weighted by area instead. */
    private Long population;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CoverageZone() {
        // for JPA
    }

    public CoverageZone(String name, String zoneType, MultiPolygon geom, Long population) {
        this.name = name;
        this.zoneType = zoneType;
        this.geom = geom;
        this.population = population;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getZoneType() {
        return zoneType;
    }

    public MultiPolygon getGeom() {
        return geom;
    }

    public Long getPopulation() {
        return population;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
