package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;
import com.dtc.transit.user.UserService;

/**
 * Revoking access while a token is still within its lifetime.
 *
 * <p>This is the property a stateless JWT does not give for free. Disabling an account or changing its
 * roles must stop the tokens already in circulation, and the token-version check is what achieves it
 * without a blacklist.
 */
class TokenRevocationTest extends SecurityWebTest {

    @Autowired
    private UserService userService;

    @Autowired
    private TokenVersionFilter tokenVersionFilter;

    @Test
    @DisplayName("disabling a user rejects their existing access token")
    void disablingUserRejectsIssuedToken() {
        var admin = support.createUser("revoke-admin", null, Role.ADMIN);
        String adminToken = support.accessTokenFor(rest, "revoke-admin");

        // The token works before the change.
        assertThat(support.call(rest, HttpMethod.GET, "/api/v1/users/" + admin.getId(), adminToken, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var victim = support.createUser("to-be-disabled", null, Role.PLANNER);
        String victimToken = support.accessTokenFor(rest, "to-be-disabled");
        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", victimToken, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Disable through the service, which bumps the token version and evicts the cache entry.
        runAsAdmin(() -> userService.update(victim.getId(), null, null, false, false));

        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", victimToken, null)
                        .getStatusCode())
                .as("a token issued before the account was disabled must stop working")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("changing roles rejects tokens carrying the old roles")
    void roleChangeRejectsIssuedToken() {
        var victim = support.createUser("role-change", 1L, Role.SCHEDULER);
        String token = support.accessTokenFor(rest, "role-change");

        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", token, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        runAsAdmin(() -> userService.update(victim.getId(), java.util.Set.of(Role.MANAGER), null, null, false));

        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", token, null)
                        .getStatusCode())
                .as("the old token still claims SCHEDULER, so it must be refused")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a re-login after a role change works immediately")
    void reloginAfterRoleChangeWorks() {
        var victim = support.createUser("relogin", 1L, Role.SCHEDULER);
        runAsAdmin(() -> userService.update(victim.getId(), java.util.Set.of(Role.MANAGER), null, null, false));

        String fresh = support.accessTokenFor(rest, "relogin");

        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", fresh, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("the cached token version is evicted so revocation does not wait out the TTL")
    void revocationDoesNotWaitForCacheExpiry() {
        var victim = support.createUser("evicted", null, Role.PLANNER);
        String token = support.accessTokenFor(rest, "evicted");

        // Warm the cache with the current version.
        support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", token, null);

        runAsAdmin(() -> userService.update(victim.getId(), null, null, false, false));

        // No sleep: the service evicts the entry, so the next request re-reads the version. Relying on
        // the 60 second TTL instead would make this test slow and the behaviour worse.
        assertThat(support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", token, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("evicting an unknown user is harmless")
    void evictUnknownUserIsSafe() {
        tokenVersionFilter.evict(123456L);
    }

    /** Runs a service call that requires ADMIN, since method security applies to direct calls too. */
    private void runAsAdmin(Runnable action) {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "test-admin",
                "n/a",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ADMIN")));
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
