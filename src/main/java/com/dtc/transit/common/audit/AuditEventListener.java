package com.dtc.transit.common.audit;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dtc.transit.common.web.CorrelationIdFilter;

/**
 * Writes audit rows in the same transaction as the change they describe.
 *
 * <p>{@code BEFORE_COMMIT} is the point of the design. An {@code AFTER_COMMIT} listener runs in a new
 * transaction, so a crash between the two would leave a committed change with no audit row, and a
 * failed audit write could not veto the change. Writing before commit makes the change and its audit
 * row succeed or fail together.
 *
 * <p>Inserts go through JDBC rather than JPA because {@code audit_log} is append-only and partitioned
 * with a composite key. Mapping it as an entity would invite someone to update or delete a row.
 */
@Component
public class AuditEventListener {

    /**
     * The JSON columns are cast in SQL rather than bound as a driver-specific object, which keeps this
     * class free of any compile-time dependency on the PostgreSQL driver.
     */
    private static final String INSERT =
            """
            INSERT INTO audit_log
                (actor, action, entity_type, entity_id, before, after, reason, trace_id)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public AuditEventListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(AuditEvent event) {
        jdbc.update(
                INSERT,
                currentActor(),
                event.action(),
                event.entityType(),
                event.entityId(),
                event.before(),
                event.after(),
                event.reason(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // Startup seeding and scheduled jobs have no security context.
            return "system";
        }
        if (authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            // Login and refresh are audited before a principal exists. Spring's anonymous principal is
            // literally named "anonymousUser", which in an audit trail reads like a real account; the
            // acting username is recorded as the entity instead.
            return "anonymous";
        }
        return authentication.getName();
    }
}
