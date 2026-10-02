package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Query-level depot scoping.
 *
 * <p>Only the branch logic is asserted here: whether a predicate is produced at all, and which depot a
 * new record must belong to. The predicate itself needs a JPA criteria query over a real entity, and
 * the first depot-scoped entity does not exist until Phase 3, where the list endpoints that depend on
 * this will cover it against the database.
 */
class DepotScopeTest {

    private final DepotScope scope = new DepotScope(new DepotAccessEvaluator());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a depot-bound caller gets a restricting predicate")
    void depotBoundCallerIsRestricted() {
        authenticateWithDepot(4L);

        assertThat(scope.restrict("depot.id")).isNotNull();
        assertThat(scope.requiredDepotId()).contains(4L);
    }

    @Test
    @DisplayName("an HQ caller gets no predicate at all")
    void hqCallerIsUnrestricted() {
        authenticateWithDepot(null);

        // Returning null rather than an always-true predicate matters: Spring Data treats null as
        // "no condition", so the generated SQL carries no redundant clause.
        assertThat(scope.restrict("depot.id")).isNull();
        assertThat(scope.requiredDepotId()).isEmpty();
    }

    @Test
    @DisplayName("an unauthenticated context is unrestricted, since the filter chain denies it earlier")
    void unauthenticatedIsUnrestricted() {
        assertThat(scope.restrict("depotId")).isNull();
        assertThat(scope.requiredDepotId()).isEmpty();
    }

    @Test
    @DisplayName("a nested attribute path is accepted as written")
    void nestedPathIsAccepted() {
        authenticateWithDepot(9L);

        // Entities reach their depot differently: some hold depotId directly, others navigate an
        // association. Both spellings must be usable by the Phase 3 specifications.
        assertThat(scope.<Object>restrict("depotId")).isNotNull();
        assertThat(scope.<Object>restrict("bus.depot.id")).isNotNull();
    }

    private static void authenticateWithDepot(Long depotId) {
        var claims = new HashMap<String, Object>(Map.of(
                "sub", "1", TokenService.CLAIM_ROLES, List.of("SCHEDULER"), TokenService.CLAIM_TOKEN_VERSION, 0));
        if (depotId != null) {
            claims.put(TokenService.CLAIM_DEPOT, depotId);
        }
        var jwt = new Jwt(
                "token-value", Instant.now(), Instant.now().plusSeconds(600), Map.of("alg", "RS256"), claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
