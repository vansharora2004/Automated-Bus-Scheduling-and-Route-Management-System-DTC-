package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.dtc.transit.common.error.NotFoundException;

/**
 * Depot scoping, including the decision to answer 404 rather than 403 for another depot's data.
 *
 * <p>This is a plain unit test. The evaluator reads the security context directly, so it can be driven
 * without a servlet container, and the rule it encodes is worth testing in isolation from any endpoint
 * that will later rely on it.
 */
class DepotAccessEvaluatorTest {

    private final DepotAccessEvaluator evaluator = new DepotAccessEvaluator();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("a depot-bound user")
    class DepotBound {

        @Test
        void canAccessTheirOwnDepot() {
            authenticateWithDepot(7L);
            assertThat(evaluator.canAccess(7L)).isTrue();
        }

        @Test
        void cannotAccessAnotherDepot() {
            authenticateWithDepot(7L);
            assertThat(evaluator.canAccess(8L)).isFalse();
        }

        @Test
        @DisplayName("another depot's resource reads as not found, not as forbidden")
        void outOfScopeLooksLikeNotFound() {
            authenticateWithDepot(7L);

            // 403 would confirm that bus 4321 exists, letting a caller enumerate other depots' ids
            // by reading status codes. 404 tells them only that there is nothing there for them.
            assertThatThrownBy(() -> evaluator.requireAccess(8L, "Bus", 4321))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Bus 4321")
                    .hasMessageContaining("not found");
        }

        @Test
        void isReportedAsDepotBound() {
            authenticateWithDepot(7L);
            assertThat(evaluator.isDepotBound()).isTrue();
            assertThat(evaluator.currentDepotId()).contains(7L);
        }

        @Test
        @DisplayName("a resource with no depot of its own is accessible")
        void nullDepotIsNotScoped() {
            authenticateWithDepot(7L);
            assertThat(evaluator.canAccess(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("an HQ user")
    class HeadQuarters {

        @Test
        void canAccessEveryDepot() {
            authenticateWithDepot(null);

            assertThat(evaluator.canAccess(1L)).isTrue();
            assertThat(evaluator.canAccess(999L)).isTrue();
            assertThat(evaluator.isDepotBound()).isFalse();
        }

        @Test
        void requireAccessNeverThrows() {
            authenticateWithDepot(null);
            assertThatCode(() -> evaluator.requireAccess(42L, "Depot", 42)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a non-JWT or absent authentication")
    class NoJwt {

        @Test
        @DisplayName("is treated as unscoped, because the filter chain has already denied anonymous access")
        void unauthenticatedHasNoDepot() {
            assertThat(evaluator.currentDepotId()).isEmpty();
            assertThat(evaluator.canAccess(5L)).isTrue();
        }

        @Test
        void nonJwtPrincipalHasNoDepot() {
            var authentication = new UsernamePasswordAuthenticationToken("someone", "n/a", List.of());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            assertThat(evaluator.currentDepotId()).isEmpty();
        }
    }

    private static void authenticateWithDepot(Long depotId) {
        var claims = new java.util.HashMap<String, Object>(
                Map.of("sub", "1", TokenService.CLAIM_ROLES, List.of("SCHEDULER"), TokenService.CLAIM_TOKEN_VERSION, 0));
        if (depotId != null) {
            claims.put(TokenService.CLAIM_DEPOT, depotId);
        }
        var jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"),
                claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
