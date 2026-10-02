package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Login throttling.
 *
 * <p>Tested directly rather than through HTTP, because the integration tests all originate from one
 * address and reset the limiter between cases. Without this test, that reset would leave the throttle
 * itself unverified.
 */
class LoginRateLimiterTest {

    private static final int PER_MINUTE = 3;

    private LoginRateLimiter limiter() {
        var properties = new SecurityProperties(
                new SecurityProperties.Jwt(
                        "iss", "aud", Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60), null, null),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.RateLimit(PER_MINUTE),
                new SecurityProperties.Cors(List.of()),
                new SecurityProperties.Bootstrap("admin", null));
        return new LoginRateLimiter(properties);
    }

    @Test
    @DisplayName("attempts are allowed up to the configured budget, then refused")
    void budgetIsEnforced() {
        var limiter = limiter();

        for (int i = 1; i <= PER_MINUTE; i++) {
            int attempt = i;
            assertThatCode(() -> limiter.checkAndConsume("alice", "10.0.0.1"))
                    .as("attempt %d should be within budget", attempt)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.checkAndConsume("alice", "10.0.0.1"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("one address spraying many usernames is still throttled")
    void sprayFromOneAddressIsThrottled() {
        var limiter = limiter();

        // Each username is fresh, so only the per-address budget can stop this. Limiting by username
        // alone would let an attacker try one password against every account from a single host.
        limiter.checkAndConsume("user-1", "10.0.0.9");
        limiter.checkAndConsume("user-2", "10.0.0.9");
        limiter.checkAndConsume("user-3", "10.0.0.9");

        assertThatThrownBy(() -> limiter.checkAndConsume("user-4", "10.0.0.9"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("one username attacked from many addresses is still throttled")
    void distributedAttackOnOneAccountIsThrottled() {
        var limiter = limiter();

        // Mirror image of the case above: varying the source address must not reset the budget for the
        // account being targeted.
        limiter.checkAndConsume("victim", "10.0.0.1");
        limiter.checkAndConsume("victim", "10.0.0.2");
        limiter.checkAndConsume("victim", "10.0.0.3");

        assertThatThrownBy(() -> limiter.checkAndConsume("victim", "10.0.0.4"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("usernames are throttled case-insensitively")
    void usernameBudgetIgnoresCase() {
        var limiter = limiter();

        limiter.checkAndConsume("Alice", "10.0.0.1");
        limiter.checkAndConsume("ALICE", "10.0.0.2");
        limiter.checkAndConsume("alice", "10.0.0.3");

        assertThatThrownBy(() -> limiter.checkAndConsume("aLiCe", "10.0.0.4"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @DisplayName("unrelated users and addresses have independent budgets")
    void budgetsAreIndependent() {
        var limiter = limiter();

        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.checkAndConsume("noisy", "10.0.0.1");
        }

        assertThatCode(() -> limiter.checkAndConsume("quiet", "10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reset clears every budget")
    void resetClearsBudgets() {
        var limiter = limiter();
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.checkAndConsume("alice", "10.0.0.1");
        }

        limiter.reset();

        assertThatCode(() -> limiter.checkAndConsume("alice", "10.0.0.1")).doesNotThrowAnyException();
    }
}
