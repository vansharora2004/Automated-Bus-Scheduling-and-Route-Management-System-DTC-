package com.dtc.transit.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searching the audit trail.
 *
 * <p>Keyset-paginated, not offset-paginated. The log grows forever and is queried newest-first, so a deep offset
 * would make the database walk and discard everything more recent than the page being asked for. A cursor costs
 * the same on page one and page five thousand, and cannot skip or repeat a row while new ones are being written.
 *
 * <p>Read-only by construction. There is no update or delete here, and Phase 11 revokes both at the database
 * level, because an audit trail somebody can edit is not evidence of anything.
 */
@Service
public class AuditLogService {

    /** Page size ceiling, matching the rest of the API. */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;

    public AuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of audit records, newest first.
     *
     * @param beforeAt and beforeId form the cursor from the previous page's last row. Both are needed: the log
     *     is partitioned by time and two records can share a timestamp, so the id breaks the tie.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional(readOnly = true)
    public Page list(
            String actor,
            String action,
            String entityType,
            String entityId,
            Instant from,
            Instant to,
            Instant beforeAt,
            Long beforeId,
            int size) {

        int limit = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("TRUE");

        if (actor != null) {
            where.append(" AND actor = ?");
            args.add(actor);
        }
        if (action != null) {
            where.append(" AND action = ?");
            args.add(action);
        }
        if (entityType != null) {
            where.append(" AND entity_type = ?");
            args.add(entityType);
        }
        if (entityId != null) {
            where.append(" AND entity_id = ?");
            args.add(entityId);
        }
        if (from != null) {
            where.append(" AND at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND at <= ?");
            args.add(java.sql.Timestamp.from(to));
        }
        if (beforeAt != null && beforeId != null) {
            // Strictly before the cursor in the same order the rows are returned in.
            where.append(" AND (at, id) < (?, ?)");
            args.add(java.sql.Timestamp.from(beforeAt));
            args.add(beforeId);
        }
        args.add(limit + 1);

        List<Entry> fetched = jdbc.query(
                """
                SELECT id, at, actor, action, entity_type, entity_id, before, after, reason, trace_id
                FROM audit_log
                WHERE %s
                ORDER BY at DESC, id DESC
                LIMIT ?
                """
                        .formatted(where),
                (rs, row) -> new Entry(
                        rs.getLong("id"),
                        rs.getTimestamp("at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("before"),
                        rs.getString("after"),
                        rs.getString("reason"),
                        rs.getString("trace_id")),
                args.toArray());

        boolean hasNext = fetched.size() > limit;
        List<Entry> content = hasNext ? fetched.subList(0, limit) : fetched;
        Entry last = content.isEmpty() ? null : content.get(content.size() - 1);

        return new Page(
                content.stream().map(AuditLogService::maskForCaller).toList(),
                content.size(),
                hasNext,
                last == null ? null : last.at(),
                last == null ? null : last.id());
    }

    /**
     * Hides the before and after payloads from anyone who is not an administrator.
     *
     * <p>Those payloads are whole entity states, and a crew record carries a licence number, an employee code and
     * a name. A manager needs to know that somebody changed something and why; they do not need the personal data
     * that was in it. Administrators see everything, because they are the ones investigating.
     *
     * <p>The masking replaces the payload rather than removing the field, so a client cannot mistake a masked
     * record for one that never had a payload.
     */
    private static Entry maskForCaller(Entry entry) {
        if (callerIsAdmin()) {
            return entry;
        }
        boolean hadBefore = entry.before() != null;
        boolean hadAfter = entry.after() != null;
        return new Entry(
                entry.id(),
                entry.at(),
                entry.actor(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                hadBefore ? MASKED : null,
                hadAfter ? MASKED : null,
                entry.reason(),
                entry.traceId());
    }

    /** What a non-administrator sees in place of a payload. */
    public static final String MASKED = "\"[redacted: administrator access required]\"";

    private static boolean callerIsAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    /**
     * @param nextBeforeAt and nextBeforeId are the cursor for the following page, null on the last page
     */
    public record Page(
            List<Entry> content, int size, boolean hasNext, Instant nextBeforeAt, Long nextBeforeId) {}

    /** @param before and after are JSON payloads, redacted for anyone who is not an administrator */
    public record Entry(
            long id,
            Instant at,
            String actor,
            String action,
            String entityType,
            String entityId,
            String before,
            String after,
            String reason,
            String traceId) {}
}
