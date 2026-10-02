package com.dtc.transit.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.user.AppUser;
import com.dtc.transit.user.Role;

/** Issues access tokens, and issues, rotates and revokes refresh tokens. */
@Service
public class TokenService {

    /** Claim carrying the user's token version, compared on every request. */
    public static final String CLAIM_TOKEN_VERSION = "tv";

    /** Claim carrying the user's depot, absent for HQ users. */
    public static final String CLAIM_DEPOT = "depot";

    /** Claim carrying role names without the {@code ROLE_} prefix. */
    public static final String CLAIM_ROLES = "roles";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityProperties properties;
    private final Clock clock;

    public TokenService(
            JwtEncoder encoder,
            RefreshTokenRepository refreshTokens,
            SecurityProperties properties,
            Clock clock) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Mints a signed access token for the user as they stand right now. */
    public String issueAccessToken(AppUser user) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.jwt().accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ROLES, user.getRoles().stream().map(Role::name).sorted().toList())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim("username", user.getUsername());
        if (user.getDepotId() != null) {
            claims.claim(CLAIM_DEPOT, user.getDepotId());
        }
        var header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
    }

    /**
     * Creates a refresh token and stores only its hash.
     *
     * @return the opaque value to hand to the client, which is never recoverable from the database
     */
    @Transactional
    public String issueRefreshToken(Long userId) {
        String value = randomValue();
        var token = new RefreshToken(
                userId, hash(value), clock.instant().plus(properties.jwt().refreshTokenTtl()));
        refreshTokens.save(token);
        return value;
    }

    /**
     * Exchanges a refresh token for a new one.
     *
     * @return the stored token that was presented, so the caller can load its user
     * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked, or replayed
     */
    @Transactional
    public Rotation rotate(String presentedValue) {
        RefreshToken stored = refreshTokens
                .findByTokenHash(hash(presentedValue))
                .orElseThrow(() -> new InvalidRefreshTokenException("unknown refresh token"));

        if (stored.isReplayed()) {
            // The token was already exchanged once. Either it leaked or a client is misbehaving;
            // either way nothing in this family can be trusted any more.
            //
            // The revocation must commit on its own, because the exception thrown on the next line
            // rolls this transaction back. Revoking inside it would undo the revocation and leave the
            // leaked family usable, which is the opposite of what reuse detection is for.
            refreshTokens.revokeAllForUserNow(stored.getUserId());
            throw new InvalidRefreshTokenException("refresh token reuse detected, token family revoked");
        }
        if (!stored.isUsable(clock.instant())) {
            throw new InvalidRefreshTokenException("refresh token is expired or revoked");
        }

        String nextValue = randomValue();
        var next = new RefreshToken(
                stored.getUserId(),
                hash(nextValue),
                clock.instant().plus(properties.jwt().refreshTokenTtl()));
        refreshTokens.save(next);

        stored.replaceWith(next.getId());
        stored.revoke();

        return new Rotation(stored.getUserId(), nextValue);
    }

    /** Revokes every live refresh token for a user, which is what logout means. */
    @Transactional
    public int revokeAll(Long userId) {
        return refreshTokens.revokeAllForUser(userId);
    }

    /** @param userId the owner of the rotated token, @param refreshToken the new opaque value */
    public record Rotation(Long userId, String refreshToken) {}

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hashes a refresh token for storage.
     *
     * <p>SHA-256 without a salt is deliberate and sufficient here: the input is 256 bits of
     * cryptographic randomness, not a human-chosen password, so there is nothing to brute-force and a
     * per-row salt would only prevent the lookup by hash that rotation depends on.
     */
    static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
