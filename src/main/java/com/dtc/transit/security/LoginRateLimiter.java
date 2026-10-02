package com.dtc.transit.security;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * Throttles login attempts per username and per client address.
 *
 * <p>Both keys matter. Limiting only by address lets one attacker spread a password-spray across many
 * accounts from a proxy pool; limiting only by username lets them hammer different accounts from one
 * address. Buckets expire so memory cannot grow without bound from spoofed keys.
 *
 * <p>This is separate from account lockout. Rate limiting slows an attacker down without locking a
 * real user out, while lockout is the harder stop after repeated failures against one account.
 */
@Component
public class LoginRateLimiter {

    private final LoadingCache<String, Bucket> buckets;

    public LoginRateLimiter(SecurityProperties properties) {
        int perMinute = properties.rateLimit().attemptsPerMinute();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(key -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(perMinute)
                                .refillGreedy(perMinute, Duration.ofMinutes(1))
                                .build())
                        .build());
    }

    /**
     * Consumes one token for each key.
     *
     * @throws RateLimitedException if either key is out of budget
     */
    public void checkAndConsume(String username, String clientAddress) {
        consume("user:" + username.toLowerCase());
        consume("ip:" + clientAddress);
    }

    /**
     * Discards all buckets.
     *
     * <p>Exists for tests, which all originate from one address and would otherwise exhaust the
     * per-address budget across unrelated cases. Production never calls this.
     */
    public void reset() {
        buckets.invalidateAll();
    }

    private void consume(String key) {
        if (!buckets.get(key).tryConsume(1)) {
            throw new RateLimitedException();
        }
    }
}
