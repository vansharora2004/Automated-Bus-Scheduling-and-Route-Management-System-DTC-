package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.SecurityTestSupport;
import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/** Login, lockout, refresh rotation and replay detection. */
class AuthenticationFlowTest extends SecurityWebTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("valid credentials return a usable token pair")
    void loginSucceeds() {
        support.createUser("alice", 1L, Role.SCHEDULER);

        var response = support.login(rest, "alice", SecurityTestSupport.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(response, "accessToken")).isNotBlank();
        assertThat(support.field(response, "refreshToken")).isNotBlank();
        assertThat(support.field(response, "tokenType")).isEqualTo("Bearer");
        assertThat(support.longField(response, "expiresIn")).isEqualTo(900);
    }

    @Test
    @DisplayName("a wrong password and an unknown username are indistinguishable")
    void loginFailuresLookIdentical() {
        support.createUser("bob", null, Role.PLANNER);

        var wrongPassword = support.login(rest, "bob", "not-the-password");
        var unknownUser = support.login(rest, "nobody-at-all", SecurityTestSupport.PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Identical bodies too: a differing message would reveal which usernames exist.
        assertThat(support.field(unknownUser, "detail")).isEqualTo(support.field(wrongPassword, "detail"));
    }

    @Test
    @DisplayName("a disabled account cannot log in")
    void disabledAccountCannotLogIn() {
        var user = support.createUser("carol", null, Role.MANAGER);
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", user.getId());

        var response = support.login(rest, "carol", SecurityTestSupport.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the failure counter survives the rejected request")
    void failureCounterIsPersisted() {
        var user = support.createUser("heidi", null, Role.PLANNER);

        support.login(rest, "heidi", "wrong");

        // The login threw, so a single surrounding transaction would have rolled this back and the
        // account could never lock no matter how many attempts were made.
        Integer failures = jdbc.queryForObject(
                "SELECT failed_logins FROM app_user WHERE id = ?", Integer.class, user.getId());
        assertThat(failures).isEqualTo(1);
    }

    @Test
    @DisplayName("the account locks after the configured number of failures")
    void lockoutAfterRepeatedFailures() {
        support.createUser("dave", 1L, Role.SCHEDULER);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(support.login(rest, "dave", "wrong").getStatusCode())
                    .as("attempt %d should still be a plain rejection", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // The sixth attempt is refused by the lockout, even with the correct password.
        var afterLock = support.login(rest, "dave", SecurityTestSupport.PASSWORD);

        assertThat(afterLock.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject("SELECT locked_until FROM app_user WHERE username = 'dave'", Object.class))
                .isNotNull();
    }

    @Test
    @DisplayName("refreshing rotates the token and returns a new access token")
    void refreshRotatesTheToken() {
        support.createUser("erin", null, Role.PLANNER);
        var first = support.tokensFor(rest, "erin");

        var refreshed = support.refresh(rest, first.refreshToken());

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(refreshed, "refreshToken")).isNotEqualTo(first.refreshToken());
        assertThat(support.field(refreshed, "accessToken")).isNotBlank();
    }

    @Test
    @DisplayName("replaying a rotated refresh token revokes the whole family")
    void refreshReuseRevokesTheFamily() {
        var user = support.createUser("frank", null, Role.MANAGER);
        var first = support.tokensFor(rest, "frank");

        var second = support.refresh(rest, first.refreshToken());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String successor = support.field(second, "refreshToken");

        // Replay the token that was already exchanged.
        assertThat(support.refresh(rest, first.refreshToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The successor must now be dead too: if one token in the chain leaked, the chain itself
        // cannot be trusted, so the whole family goes rather than only the replayed row.
        assertThat(support.refresh(rest, successor).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked = false",
                Integer.class,
                user.getId());
        assertThat(live).isZero();
    }

    @Test
    @DisplayName("an unknown refresh token is rejected")
    void unknownRefreshTokenRejected() {
        assertThat(support.refresh(rest, "not-a-real-token").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("logout revokes every refresh token for the user")
    void logoutRevokesRefreshTokens() {
        var user = support.createUser("grace", null, Role.PLANNER);
        var pair = support.tokensFor(rest, "grace");

        var logout = support.call(rest, HttpMethod.POST, "/api/v1/auth/logout", pair.accessToken(), null);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(support.refresh(rest, pair.refreshToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked = false",
                Integer.class,
                user.getId());
        assertThat(live).isZero();
    }
}
