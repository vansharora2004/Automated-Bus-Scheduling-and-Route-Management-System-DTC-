package com.dtc.transit.route.pattern;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.MultiPolygon;

/**
 * The operating boundary used to validate submitted geometry.
 *
 * <p>Exactly one row is active at a time, enforced by a partial unique index, so validation never has to
 * choose between two boundaries.
 */
@Entity
@Table(name = "service_area")
public class ServiceArea {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "service_area_seq")
    @SequenceGenerator(name = "service_area_seq", sequenceName = "service_area_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "geometry(MultiPolygon,4326)", nullable = false)
    private MultiPolygon geom;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ServiceArea() {
        // for JPA
    }

    public ServiceArea(String name, MultiPolygon geom) {
        this.name = name;
        this.geom = geom;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MultiPolygon getGeom() {
        return geom;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
