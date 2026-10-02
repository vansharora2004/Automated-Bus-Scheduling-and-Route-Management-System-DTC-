package com.dtc.transit.reporting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.security.DepotScope;

/**
 * KPI reports over published schedules.
 *
 * <p>Every query here reads a materialized view, and every view filters on {@code status = 'PUBLISHED'}. That
 * filter is the whole correctness story: a republished day leaves a SUPERSEDED version carrying the same blocks
 * and duties, and including it would double count every figure in every report.
 *
 * <p>Nothing in this class writes. Reports read what scheduling published; a report that could change a schedule
 * would make the numbers unexplainable.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** Page size ceiling, matching the rest of the API rather than inventing a new limit. */
    public static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final DepotScope depotScope;

    public ReportService(JdbcTemplate jdbc, DepotScope depotScope) {
        this.jdbc = jdbc;
        this.depotScope = depotScope;
    }

    /**
     * Fleet utilization per depot and service date.
     *
     * @param depotId null for every depot the caller may see
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<FleetUtilizationRow> fleetUtilization(
            Long depotId, LocalDate from, LocalDate to, int page, int size) {

        var filter = depotFilter(depotId, from, to);
        int limit = clamp(size);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit + 1);
        args.add((long) page * limit);

        List<FleetUtilizationRow> rows = jdbc.query(
                """
                SELECT depot_id, service_date, blocks, buses_used, peak_vehicles,
                       service_km, dead_km, dead_km_ratio, in_service_ratio
                FROM mv_fleet_utilization_daily
                WHERE %s
                ORDER BY service_date DESC, depot_id
                LIMIT ? OFFSET ?
                """
                        .formatted(filter.sql()),
                (rs, row) -> new FleetUtilizationRow(
                        rs.getLong("depot_id"),
                        rs.getDate("service_date").toLocalDate(),
                        rs.getInt("blocks"),
                        rs.getInt("buses_used"),
                        rs.getInt("peak_vehicles"),
                        rs.getDouble("service_km"),
                        rs.getDouble("dead_km"),
                        nullableDouble(rs, "dead_km_ratio"),
                        nullableDouble(rs, "in_service_ratio")),
                args.toArray());

        return Page.of(rows, page, limit);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<CrewHoursRow> crewHours(Long depotId, Integer isoYear, Integer isoWeek, int page, int size) {
        int limit = clamp(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("TRUE");

        Long scoped = depotScope.requiredDepotId().orElse(depotId);
        if (scoped != null) {
            where.append(" AND depot_id = ?");
            args.add(scoped);
        }
        if (isoYear != null) {
            where.append(" AND iso_year = ?");
            args.add(isoYear);
        }
        if (isoWeek != null) {
            where.append(" AND iso_week = ?");
            args.add(isoWeek);
        }
        args.add(limit + 1);
        args.add((long) page * limit);

        List<CrewHoursRow> rows = jdbc.query(
                """
                SELECT crew_member_id, iso_year, iso_week, depot_id, duties, work_sec, paid_sec,
                       platform_sec, overtime_sec, night_duties, split_duties
                FROM mv_crew_hours_weekly
                WHERE %s
                ORDER BY iso_year DESC, iso_week DESC, crew_member_id
                LIMIT ? OFFSET ?
                """
                        .formatted(where),
                (rs, row) -> new CrewHoursRow(
                        rs.getLong("crew_member_id"),
                        rs.getInt("iso_year"),
                        rs.getInt("iso_week"),
                        rs.getLong("depot_id"),
                        rs.getInt("duties"),
                        rs.getLong("work_sec"),
                        rs.getLong("paid_sec"),
                        rs.getLong("platform_sec"),
                        rs.getLong("overtime_sec"),
                        rs.getInt("night_duties"),
                        rs.getInt("split_duties")),
                args.toArray());

        return Page.of(rows, page, limit);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional(readOnly = true)
    public Page<ScheduleKpiRow> scheduleKpis(Long depotId, LocalDate from, LocalDate to, int page, int size) {
        var filter = depotFilter(depotId, from, to);
        int limit = clamp(size);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit + 1);
        args.add((long) page * limit);

        List<ScheduleKpiRow> rows = jdbc.query(
                """
                SELECT schedule_id, depot_id, service_date, version_no, blocks, trips, duties,
                       duties_assigned, duties_unassigned, split_duties, overtime_sec,
                       platform_to_paid_ratio, hard_conflicts, soft_conflicts
                FROM mv_schedule_kpis
                WHERE %s
                ORDER BY service_date DESC, depot_id
                LIMIT ? OFFSET ?
                """
                        .formatted(filter.sql()),
                (rs, row) -> new ScheduleKpiRow(
                        rs.getLong("schedule_id"),
                        rs.getLong("depot_id"),
                        rs.getDate("service_date").toLocalDate(),
                        rs.getInt("version_no"),
                        rs.getInt("blocks"),
                        rs.getInt("trips"),
                        rs.getInt("duties"),
                        rs.getInt("duties_assigned"),
                        rs.getInt("duties_unassigned"),
                        rs.getInt("split_duties"),
                        rs.getLong("overtime_sec"),
                        nullableDouble(rs, "platform_to_paid_ratio"),
                        rs.getInt("hard_conflicts"),
                        rs.getInt("soft_conflicts")),
                args.toArray());

        return Page.of(rows, page, limit);
    }

    /**
     * Streams fleet utilization rows to a consumer, for CSV export.
     *
     * <p>Row by row rather than a list. An export of a year across 45 depots is sixteen thousand rows, and
     * building the whole thing in memory to write it out is the kind of endpoint that works until someone
     * exports two years.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public void streamFleetUtilization(
            Long depotId, LocalDate from, LocalDate to, java.util.function.Consumer<FleetUtilizationRow> sink) {

        var filter = depotFilter(depotId, from, to);
        jdbc.setFetchSize(500);
        jdbc.query(
                """
                SELECT depot_id, service_date, blocks, buses_used, peak_vehicles,
                       service_km, dead_km, dead_km_ratio, in_service_ratio
                FROM mv_fleet_utilization_daily
                WHERE %s
                ORDER BY service_date DESC, depot_id
                """
                        .formatted(filter.sql()),
                rs -> {
                    sink.accept(new FleetUtilizationRow(
                            rs.getLong("depot_id"),
                            rs.getDate("service_date").toLocalDate(),
                            rs.getInt("blocks"),
                            rs.getInt("buses_used"),
                            rs.getInt("peak_vehicles"),
                            rs.getDouble("service_km"),
                            rs.getDouble("dead_km"),
                            nullableDouble(rs, "dead_km_ratio"),
                            nullableDouble(rs, "in_service_ratio")));
                },
                filter.args().toArray());
    }

    /**
     * Rebuilds the report views.
     *
     * <p>{@code NOT_SUPPORTED}, because {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} cannot run inside a
     * transaction. Concurrently is the point: a plain refresh takes an exclusive lock and every report blocks
     * behind it for the duration.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshAll() {
        for (String view : List.of("mv_fleet_utilization_daily", "mv_crew_hours_weekly", "mv_schedule_kpis")) {
            try {
                jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
            } catch (Exception e) {
                // CONCURRENTLY needs the view to have been populated at least once. Falling back is correct on
                // a fresh database rather than failing the first refresh it ever sees.
                log.debug("falling back to a locking refresh of {}: {}", view, e.getMessage());
                jdbc.execute("REFRESH MATERIALIZED VIEW " + view);
            }
        }
        log.info("refreshed the reporting views");
    }

    /**
     * The depot and date filter, with the caller's own depot forced in where they have one.
     *
     * <p>A depot-bound scheduler asking for every depot gets their own, not an error. Letting the parameter
     * widen their view would be a data leak through a report, which is exactly where nobody looks for one.
     */
    private Filter depotFilter(Long depotId, LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("TRUE");

        Long scoped = depotScope.requiredDepotId().orElse(depotId);
        if (scoped != null) {
            sql.append(" AND depot_id = ?");
            args.add(scoped);
        }
        if (from != null) {
            sql.append(" AND service_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND service_date <= ?");
            args.add(to);
        }
        return new Filter(sql.toString(), args);
    }

    /**
     * A nullable ratio from the result set.
     *
     * <p>Read as a {@code BigDecimal} and converted. PostgreSQL types these division expressions as
     * {@code numeric}, which the driver hands back as a {@code BigDecimal}, so casting the object straight to a
     * {@code Double} fails at runtime rather than at compile time.
     */
    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static int clamp(int size) {
        // Clamped rather than rejected, matching the Phase 3 paging behaviour so one list endpoint does not
        // behave differently from the rest.
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private record Filter(String sql, List<Object> args) {}

    /**
     * One page of report rows.
     *
     * <p>Its own small type rather than Spring's {@code Page}: these come from materialized views read with
     * JDBC, and a count query over a view to populate a total nobody displays would double the cost of every
     * report.
     */
    public record Page<T>(List<T> content, int page, int size, boolean hasNext) {

        static <T> Page<T> of(List<T> fetched, int page, int limit) {
            boolean hasNext = fetched.size() > limit;
            List<T> content = hasNext ? fetched.subList(0, limit) : fetched;
            return new Page<>(List.copyOf(content), page, content.size(), hasNext);
        }
    }

    /** @param deadKmRatio and inServiceRatio are null for a depot-day with no running, not zero */
    public record FleetUtilizationRow(
            long depotId,
            LocalDate serviceDate,
            int blocks,
            int busesUsed,
            int peakVehicles,
            double serviceKm,
            double deadKm,
            Double deadKmRatio,
            Double inServiceRatio) {}

    public record CrewHoursRow(
            long crewMemberId,
            int isoYear,
            int isoWeek,
            long depotId,
            int duties,
            long workSec,
            long paidSec,
            long platformSec,
            long overtimeSec,
            int nightDuties,
            int splitDuties) {}

    /** @param platformToPaidRatio null when nothing was paid, which is the headline crew efficiency figure */
    public record ScheduleKpiRow(
            long scheduleId,
            long depotId,
            LocalDate serviceDate,
            int versionNo,
            int blocks,
            int trips,
            int duties,
            int dutiesAssigned,
            int dutiesUnassigned,
            int splitDuties,
            long overtimeSec,
            Double platformToPaidRatio,
            int hardConflicts,
            int softConflicts) {}
}
