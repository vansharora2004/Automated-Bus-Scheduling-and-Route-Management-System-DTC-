package com.dtc.transit.user;

import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.filtering.Filters;
import com.dtc.transit.common.paging.SortWhitelist;
import com.dtc.transit.security.TokenVersionFilter;

/**
 * User administration.
 *
 * <p>Authorization is declared here rather than only on the controller, so that any future caller that
 * does not arrive over HTTP is still checked.
 */
@Service
public class UserService {

    /** Sortable properties. The password hash is deliberately absent. */
    public static final Set<String> SORTABLE = Set.of("username", "enabled", "depotId");

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final TokenVersionFilter tokenVersionFilter;
    private final SortWhitelist sortWhitelist;

    public UserService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            TokenVersionFilter tokenVersionFilter,
            SortWhitelist sortWhitelist) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.tokenVersionFilter = tokenVersionFilter;
        this.sortWhitelist = sortWhitelist;
    }

    /**
     * Lists accounts.
     *
     * <p>Deferred from Phase 2 so it could be built on the shared paging framework rather than growing
     * its own and then being rewritten.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<AppUser> search(Boolean enabled, Long depotId, String q, Pageable pageable) {
        Specification<AppUser> spec = Specification.allOf(
                Filters.eq("enabled", enabled), Filters.eq("depotId", depotId), Filters.contains("username", q));
        return users.findAll(spec, sortWhitelist.apply(pageable, SORTABLE));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser create(String username, String rawPassword, Set<Role> roles, Long depotId) {
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("USERNAME_TAKEN", "Username '" + username + "' already exists");
        }
        var user = new AppUser(username, passwordEncoder.encode(rawPassword), roles, depotId);
        users.save(user);

        events.publishEvent(AuditEvent.created("APP_USER", user.getId(), describe(user)));
        return user;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Changes roles, depot or enabled state.
     *
     * <p>Each of these alters what an already-issued token may do, so the entity bumps its token
     * version and the cached version is evicted. Without the eviction the change would wait out the
     * cache TTL even though the database already knows.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser update(Long id, Set<Role> roles, Long depotId, Boolean enabled, boolean clearDepot) {
        AppUser user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        String before = describe(user);

        if (roles != null && !roles.isEmpty()) {
            user.setRoles(roles);
        }
        if (clearDepot) {
            user.setDepotId(null);
        } else if (depotId != null) {
            user.setDepotId(depotId);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        users.save(user);
        tokenVersionFilter.evict(id);

        events.publishEvent(AuditEvent.updated("APP_USER", id, before, describe(user)));
        return user;
    }

    /** Compact JSON snapshot for the audit trail. The password hash is never included. */
    private static String describe(AppUser user) {
        String roles = user.getRoles().stream()
                .map(Role::name)
                .sorted()
                .reduce((a, b) -> a + "\",\"" + b)
                .map(joined -> "[\"" + joined + "\"]")
                .orElse("[]");
        return """
                {"username":"%s","enabled":%s,"depotId":%s,"roles":%s,"tokenVersion":%d}"""
                .formatted(
                        user.getUsername(),
                        user.isEnabled(),
                        user.getDepotId() == null ? "null" : user.getDepotId(),
                        roles,
                        user.getTokenVersion());
    }
}
