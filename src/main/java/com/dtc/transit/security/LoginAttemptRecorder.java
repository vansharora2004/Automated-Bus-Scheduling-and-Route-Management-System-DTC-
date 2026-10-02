package com.dtc.transit.security;

import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.user.AppUser;
import com.dtc.transit.user.AppUserRepository;

/**
 * Records the outcome of a login attempt in its own transaction.
 *
 * <p>This exists because a failed login has to be remembered even though the request fails. If the
 * counter were incremented inside the transaction that then throws, the rollback would undo it and the
 * account would never lock, no matter how many attempts were made. The audit row has the same problem:
 * a {@code BEFORE_COMMIT} listener never fires on a transaction that rolls back, so every failed login
 * would vanish from the trail.
 *
 * <p>Each method therefore runs in a new transaction that commits on its own. It is a separate bean
 * rather than a method on {@link AuthService} because Spring's transaction proxy is bypassed by
 * self-invocation, so {@code REQUIRES_NEW} on a sibling method would silently do nothing.
 */
@Service
public class LoginAttemptRecorder {

    private final AppUserRepository users;
    private final ApplicationEventPublisher events;
    private final SecurityProperties properties;
    private final Clock clock;

    public LoginAttemptRecorder(
            AppUserRepository users,
            ApplicationEventPublisher events,
            SecurityProperties properties,
            Clock clock) {
        this.users = users;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Audits an attempt against a username that does not exist. There is no counter to update. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownUser(String username) {
        audit("LOGIN_FAILED", username, "unknown username");
    }

    /** Audits an attempt refused because a lockout is already in force. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlocked(String username, String reason) {
        audit("LOGIN_BLOCKED", username, reason);
    }

    /** Audits a rejection that must not advance the lockout counter, such as a disabled account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(String username, String reason) {
        audit("LOGIN_FAILED", username, reason);
    }

    /** Increments the failure counter, locking the account once the threshold is reached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBadPassword(Long userId, String username) {
        users.findById(userId).ifPresent(user -> {
            user.recordFailedLogin(
                    properties.lockout().maxFailures(), properties.lockout().duration(), clock.instant());
            users.save(user);
        });
        audit("LOGIN_FAILED", username, "bad password");
    }

    /** Clears the failure counter and any lockout, and audits the success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId, String username) {
        users.findById(userId).ifPresent(user -> {
            user.recordSuccessfulLogin();
            users.save(user);
        });
        audit("LOGIN_SUCCEEDED", username, null);
    }

    /** Re-reads the user after the recorder's own transaction has committed. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AppUser reload(Long userId) {
        return users.findById(userId).orElseThrow(AuthenticationFailedException::new);
    }

    private void audit(String action, String username, String reason) {
        events.publishEvent(new AuditEvent(action, "APP_USER", username, null, null, reason));
    }
}
