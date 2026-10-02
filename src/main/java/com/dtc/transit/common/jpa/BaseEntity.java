package com.dtc.transit.common.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Fields every editable aggregate carries.
 *
 * <p>{@code version} drives optimistic locking. Two schedulers editing the same bus must not silently
 * overwrite each other: the second write fails with a stale-version error and the client reloads.
 * Pessimistic locking would serialise ordinary edits for no benefit, since conflicts are rare.
 *
 * <p>The timestamps are maintained by the database defaults, so they are read-only here and cannot be
 * forged by a request body.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
