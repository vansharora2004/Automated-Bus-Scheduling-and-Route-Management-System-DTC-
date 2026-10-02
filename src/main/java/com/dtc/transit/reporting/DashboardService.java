package com.dtc.transit.reporting;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.security.DepotScope;

/**
 * The live operations snapshot for today.
 *
 * <p>Reads the live tables, not the materialized views. "What is happening now" cannot wait for a refresh, and a
 * dashboard showing a view that was rebuilt an hour ago would be worse than no dashboard: it would look current.
 *
 * <p>Cached for thirty seconds instead. An operations screen on a wall polls every few seconds, and the
 * underlying queries scan today's duties and assignments; thirty seconds is short enough that nobody notices and
 * long enough that fifty open screens cost one query.
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** How long a snapshot is served before it is rebuilt. */
    public static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final DepotScope depotScope;
    private final AppTimeProperties timeProperties;
    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DashboardService(
            JdbcTemplate jdbc, DepotScope depotScope, AppTimeProperties timeProperties, Clock clock) {
        this.jdbc = jdbc;
        this.depotScope = depotScope;
        this.timeProperties = timeProperties;
        this.clock = clock;
    }

    /**
     * Today's snapshot for a depot, or for every depot the caller may see.
     *
     * <p>The service date, not the calendar date. Before the service day starts at 03:00 the operational "today"
     * is still yesterday, and a dashboard that rolled over at midnight would show an empty screen to the people
     * running the night service.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    public Snapshot today(Long depotId) {
        Long scoped = depotScope.requiredDepotId().orElse(depotId);
        LocalDate serviceDate = currentServiceDate();
        String key = scoped + "@" + serviceDate;

        Cached cached = cache.get(key);
        if (cached != null && !cached.isStale(clock.instant())) {
            return cached.snapshot();
        }

        Snapshot snapshot = build(scoped, serviceDate);
        cache.put(key, new Cached(snapshot, clock.instant().plus(CACHE_TTL)));
        return snapshot;
    }

    /**
     * The service date now in force.
     *
     * <p>Derived from the configured service-day start, so a 02:00 query belongs to the previous service date
     * (edge case EC-TIME-03).
     */
    public LocalDate currentServiceDate() {
        var now = clock.instant().atZone(timeProperties.zone());
        return now.toLocalTime().isBefore(timeProperties.serviceDayStart())
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
    }

    @Transactional(readOnly = true)
    protected Snapshot build(Long depotId, LocalDate serviceDate) {
        String depotClause = depotId == null ? "" : " AND s.depot_id = " + depotId;

        var totals = jdbc.queryForMap(
                """
                SELECT COALESCE(count(DISTINCT s.id), 0)      AS schedules,
                       COALESCE(sum(k.blocks), 0)             AS blocks,
                       COALESCE(sum(k.trips), 0)              AS trips,
                       COALESCE(sum(k.duties), 0)             AS duties,
                       COALESCE(sum(k.duties_assigned), 0)    AS duties_assigned,
                       COALESCE(sum(k.duties_unassigned), 0)  AS duties_unassigned,
                       COALESCE(sum(k.hard_conflicts), 0)     AS hard_conflicts
                FROM schedule s
                LEFT JOIN mv_schedule_kpis k ON k.schedule_id = s.id
                WHERE s.status = 'PUBLISHED' AND s.service_date = ?%s
                """
                        .formatted(depotClause),
                serviceDate);

        // Live counts, straight off the operational tables rather than the views.
        Integer onDuty = jdbc.queryForObject(
                """
                SELECT count(*) FROM duty_assignment da
                JOIN duty d ON d.id = da.duty_id
                JOIN schedule s ON s.id = d.schedule_id
                WHERE s.status = 'PUBLISHED' AND da.service_date = ? AND da.status <> 'CANCELLED'%s
                """
                        .formatted(depotClause),
                Integer.class,
                serviceDate);

        Integer busesOut = jdbc.queryForObject(
                """
                SELECT count(DISTINCT a.bus_id) FROM bus_assignment a
                JOIN vehicle_block b ON b.id = a.block_id
                JOIN schedule s ON s.id = b.schedule_id
                WHERE s.status = 'PUBLISHED' AND s.service_date = ?%s
                """
                        .formatted(depotClause),
                Integer.class,
                serviceDate);

        List<RunSummary> activeRuns = jdbc.query(
                """
                SELECT id, depot_id, service_date, status, progress FROM schedule_run
                WHERE status IN ('QUEUED', 'RUNNING')%s
                ORDER BY created_at
                """
                        .formatted(depotId == null ? "" : " AND depot_id = " + depotId),
                (rs, row) -> new RunSummary(
                        rs.getString("id"),
                        rs.getLong("depot_id"),
                        rs.getDate("service_date").toLocalDate(),
                        rs.getString("status"),
                        rs.getInt("progress")));

        Integer needingRevalidation = jdbc.queryForObject(
                """
                SELECT count(*) FROM schedule s
                WHERE s.status = 'PUBLISHED' AND s.needs_revalidation AND s.service_date >= ?%s
                """
                        .formatted(depotClause),
                Integer.class,
                serviceDate);

        return new Snapshot(
                serviceDate,
                depotId,
                ServiceTime.format((int) secondsIntoServiceDay()),
                ((Number) totals.get("schedules")).intValue(),
                ((Number) totals.get("blocks")).intValue(),
                ((Number) totals.get("trips")).intValue(),
                ((Number) totals.get("duties")).intValue(),
                ((Number) totals.get("duties_assigned")).intValue(),
                ((Number) totals.get("duties_unassigned")).intValue(),
                ((Number) totals.get("hard_conflicts")).intValue(),
                onDuty == null ? 0 : onDuty,
                busesOut == null ? 0 : busesOut,
                needingRevalidation == null ? 0 : needingRevalidation,
                activeRuns,
                clock.instant());
    }

    private long secondsIntoServiceDay() {
        var now = clock.instant().atZone(timeProperties.zone());
        long seconds = now.toLocalTime().toSecondOfDay();
        // Past midnight but before the service-day start still belongs to the previous day, so the clock runs
        // past 86,400 rather than resetting.
        return now.toLocalTime().isBefore(timeProperties.serviceDayStart()) ? seconds + 86_400 : seconds;
    }

    /** Clears the cache, so a test or an operator can force a rebuild. */
    public void invalidate() {
        cache.clear();
        log.debug("dashboard cache cleared");
    }

    private record Cached(Snapshot snapshot, java.time.Instant expiresAt) {

        boolean isStale(java.time.Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * @param depotId null when the snapshot spans every depot the caller may see
     * @param generatedAt when this snapshot was built, so a client can tell a cached read from a fresh one
     */
    public record Snapshot(
            LocalDate serviceDate,
            Long depotId,
            String serviceTimeNow,
            int publishedSchedules,
            int blocks,
            int trips,
            int duties,
            int dutiesAssigned,
            int dutiesUnassigned,
            int hardConflicts,
            int crewOnDuty,
            int busesOut,
            int schedulesNeedingRevalidation,
            List<RunSummary> activeRuns,
            java.time.Instant generatedAt) {}

    public record RunSummary(String runId, long depotId, LocalDate serviceDate, String status, int progress) {}
}
