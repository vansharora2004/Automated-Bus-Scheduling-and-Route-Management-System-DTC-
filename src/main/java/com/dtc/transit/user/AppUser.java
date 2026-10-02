package com.dtc.transit.user;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An account that can authenticate against the API.
 *
 * <p>A null {@code depotId} marks an HQ user, who is not bound to one depot and may act across all of
 * them. Every other user is restricted to their own depot by the depot scope applied in later phases.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq")
    @SequenceGenerator(name = "app_user_seq", sequenceName = "app_user_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "depot_id")
    private Long depotId;

    /**
     * Incremented whenever this account's access should stop being trusted.
     *
     * <p>Access tokens embed the value current at issue time. Raising it invalidates every token
     * already in circulation without maintaining a blacklist.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String passwordHash, Set<Role> roles, Long depotId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = EnumSet.copyOf(roles);
        this.depotId = depotId;
    }

    /** True when a lockout is currently in force. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Records a failed attempt, locking the account once the threshold is reached. */
    public void recordFailedLogin(int maxFailures, java.time.Duration lockDuration, Instant now) {
        failedLogins++;
        if (failedLogins >= maxFailures) {
            lockedUntil = now.plus(lockDuration);
            failedLogins = 0;
        }
    }

    /** Clears the failure counter and any lockout after a successful login. */
    public void recordSuccessfulLogin() {
        failedLogins = 0;
        lockedUntil = null;
    }

    /**
     * Invalidates every access token already issued to this user.
     *
     * <p>Called on disable, role change and depot change, because all three alter what the token is
     * allowed to do.
     */
    public void invalidateIssuedTokens() {
        tokenVersion++;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            invalidateIssuedTokens();
        }
    }

    public void setRoles(Set<Role> roles) {
        this.roles = EnumSet.copyOf(roles);
        invalidateIssuedTokens();
    }

    public void setDepotId(Long depotId) {
        this.depotId = depotId;
        invalidateIssuedTokens();
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        invalidateIssuedTokens();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Long getDepotId() {
        return depotId;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public int getFailedLogins() {
        return failedLogins;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

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
