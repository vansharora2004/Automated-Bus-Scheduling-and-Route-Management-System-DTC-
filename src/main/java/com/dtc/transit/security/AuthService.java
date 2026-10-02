package com.dtc.transit.security;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.user.AppUser;
import com.dtc.transit.user.AppUserRepository;

/**
 * Login, refresh and logout.
 *
 * <p>{@code login} is deliberately not transactional. Every failure path throws, and a surrounding
 * transaction would roll back exactly the bookkeeping a failure has to leave behind: the attempt
 * counter that drives lockout, and the audit row. {@link LoginAttemptRecorder} commits those in their
 * own transactions instead.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A well-formed bcrypt hash of a value nobody knows, verified against when the username does not
     * exist so that both paths cost the same.
     */
    private static final String TIMING_EQUALISER_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final LoginAttemptRecorder attempts;
    private final SecurityProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AuthService(
            AppUserRepository users,
            TokenService tokens,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter rateLimiter,
            LoginAttemptRecorder attempts,
            SecurityProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.attempts = attempts;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Authenticates a user and issues a token pair.
     *
     * @throws RateLimitedException if attempts for this username or address are exhausted
     * @throws AccountLockedException if a lockout is in force
     * @throws AuthenticationFailedException for every other failure, with one message for all of them
     */
    public TokenPair login(String username, String rawPassword, String clientAddress) {
        rateLimiter.checkAndConsume(username, clientAddress);

        Instant now = clock.instant();
        var found = users.findByUsernameIgnoreCase(username);

        if (found.isEmpty()) {
            // Verify against a dummy hash anyway. Returning immediately for an unknown username makes
            // the response measurably faster than for a known one, and that timing difference is
            // enough to enumerate accounts.
            passwordEncoder.matches(rawPassword, TIMING_EQUALISER_HASH);
            attempts.recordUnknownUser(username);
            throw new AuthenticationFailedException();
        }

        AppUser user = found.get();

        if (user.isLocked(now)) {
            attempts.recordBlocked(username, "account locked");
            throw new AccountLockedException();
        }

        if (!user.isEnabled()) {
            attempts.recordRejected(username, "account disabled");
            throw new AuthenticationFailedException();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            attempts.recordBadPassword(user.getId(), username);
            throw new AuthenticationFailedException();
        }

        attempts.recordSuccess(user.getId(), username);
        log.debug("login succeeded for user {}", user.getId());

        // Re-read after the recorder committed, so the token reflects the cleared lockout state.
        AppUser fresh = attempts.reload(user.getId());
        return new TokenPair(
                tokens.issueAccessToken(fresh),
                tokens.issueRefreshToken(fresh.getId()),
                properties.jwt().accessTokenTtl().toSeconds());
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>The access token is rebuilt from the user's current state, so a role or depot change takes
     * effect at the next refresh without the client doing anything.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        var rotation = tokens.rotate(refreshToken);
        AppUser user = users.findById(rotation.userId())
                .orElseThrow(() -> new InvalidRefreshTokenException("user no longer exists"));

        if (!user.isEnabled()) {
            tokens.revokeAll(user.getId());
            throw new InvalidRefreshTokenException("account is disabled");
        }

        return new TokenPair(
                tokens.issueAccessToken(user),
                rotation.refreshToken(),
                properties.jwt().accessTokenTtl().toSeconds());
    }

    /** Revokes every refresh token for the user. Access tokens still expire on their own. */
    @Transactional
    public void logout(Long userId) {
        int revoked = tokens.revokeAll(userId);
        events.publishEvent(new AuditEvent(
                "LOGOUT", "APP_USER", String.valueOf(userId), null, null, revoked + " token(s) revoked"));
    }

    /**
     * @param accessToken  short-lived signed JWT
     * @param refreshToken opaque value, valid once
     * @param expiresIn    access token lifetime in seconds
     */
    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
}
