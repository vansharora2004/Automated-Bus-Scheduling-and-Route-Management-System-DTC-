package com.dtc.transit.masterdata.crew;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A qualification a crew member holds, such as EV or AC training.
 *
 * <p>The code is free text rather than an enum: new training types appear without a release, and a
 * route-specific endorsement is written as {@code ROUTE_534}. Assignment in Phase 9 matches on the code
 * a trip requires.
 */
@Entity
@Table(name = "crew_qualification")
public class CrewQualification {

    @EmbeddedId
    private Key key;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CrewQualification() {
        // for JPA
    }

    public CrewQualification(Long crewMemberId, String code, LocalDate validUntil) {
        this.key = new Key(crewMemberId, code.toUpperCase());
        this.validUntil = validUntil;
    }

    /** Valid when it has no expiry, or has not expired on the given date. */
    public boolean isValidOn(LocalDate date) {
        return validUntil == null || !validUntil.isBefore(date);
    }

    public Long getCrewMemberId() {
        return key.crewMemberId();
    }

    public String getCode() {
        return key.code();
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite key: one row per crew member and code. */
    @Embeddable
    public record Key(@Column(name = "crew_member_id") Long crewMemberId, @Column(name = "code") String code)
            implements Serializable {

        public Key {
            Objects.requireNonNull(crewMemberId, "crewMemberId");
            Objects.requireNonNull(code, "code");
        }
    }
}
