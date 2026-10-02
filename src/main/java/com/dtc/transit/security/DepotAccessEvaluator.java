package com.dtc.transit.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.dtc.transit.common.error.NotFoundException;

/**
 * Decides whether the current caller may touch data belonging to a given depot.
 *
 * <p>A caller with no depot claim is an HQ user and may act across every depot. Everyone else is
 * confined to their own.
 *
 * <p>Registered as {@code depotAccess} so expressions can read naturally:
 *
 * <pre>{@code @PreAuthorize("hasRole('SCHEDULER') and @depotAccess.canAccess(#depotId)")}</pre>
 */
@Component("depotAccess")
public class DepotAccessEvaluator {

    /** True when the caller may act on the given depot. A null depot means "not depot-scoped". */
    public boolean canAccess(Long depotId) {
        Optional<Long> callerDepot = currentDepotId();
        if (callerDepot.isEmpty()) {
            return true;
        }
        return depotId == null || callerDepot.get().equals(depotId);
    }

    /**
     * Asserts access, treating a refusal as a missing resource.
     *
     * <p>This throws 404 rather than 403 on purpose. A 403 confirms that the id exists, which lets a
     * depot-bound caller enumerate other depots' identifiers by probing for the status code
     * (edge case EC-SEC-01). The caller learns only that nothing is there for them.
     */
    public void requireAccess(Long depotId, String entityType, Object entityId) {
        if (!canAccess(depotId)) {
            throw new NotFoundException(entityType + " " + entityId + " was not found");
        }
    }

    /** The caller's depot, empty for an HQ user or an unauthenticated context. */
    public Optional<Long> currentDepotId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        Object claim = jwt.getClaim(TokenService.CLAIM_DEPOT);
        if (claim instanceof Number number) {
            return Optional.of(number.longValue());
        }
        return Optional.empty();
    }

    /** True when the caller is bound to a single depot. */
    public boolean isDepotBound() {
        return currentDepotId().isPresent();
    }
}
