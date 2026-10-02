package com.dtc.transit.security;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings. Every value is configurable, and none carries a usable secret as a default.
 *
 * @param jwt       token issuing and validation
 * @param lockout   account lockout after repeated failures
 * @param rateLimit login throttling
 * @param cors      allowed browser origins
 * @param bootstrap the first administrator, for local use only
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotNull Jwt jwt,
        @NotNull Lockout lockout,
        @NotNull RateLimit rateLimit,
        @NotNull Cors cors,
        @NotNull Bootstrap bootstrap) {

    /**
     * @param issuer         the {@code iss} claim, also checked on validation
     * @param audience       the {@code aud} claim, also checked on validation
     * @param accessTokenTtl how long an access token stays valid; short, because revocation is
     *     bounded by it
     * @param refreshTokenTtl how long a refresh token stays valid
     * @param tokenVersionCacheTtl how long a token version is cached before being re-read, which sets
     *     the worst-case delay before a disabled account stops working
     * @param privateKey PKCS#8 PEM; when blank an ephemeral key pair is generated at startup
     * @param publicKey  X.509 PEM; when blank an ephemeral key pair is generated at startup
     */
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            @NotNull Duration tokenVersionCacheTtl,
            String privateKey,
            String publicKey) {}

    /**
     * @param maxFailures consecutive failures before the account locks
     * @param duration    how long the lock lasts
     */
    public record Lockout(@Min(1) int maxFailures, @NotNull Duration duration) {}

    /**
     * @param attemptsPerMinute login attempts allowed per minute, counted per username and per client
     *     address
     */
    public record RateLimit(@Min(1) int attemptsPerMinute) {}

    /** @param allowedOrigins exact origins allowed to call the API from a browser; never a wildcard */
    public record Cors(@NotNull List<String> allowedOrigins) {}

    /**
     * @param username the administrator created at startup when the table is empty
     * @param password supplied through the environment; when blank no account is created
     */
    public record Bootstrap(String username, String password) {}
}
