package com.dtc.transit.scheduling.crew;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.scheduling.engine.model.CrewHistory;
import com.dtc.transit.scheduling.engine.model.CrewPool;
import com.dtc.transit.scheduling.engine.model.CrewView;
import com.dtc.transit.scheduling.engine.model.TimeWindow;

/**
 * Loads the crew a depot can call on, and what they have already worked.
 *
 * <p>Two windows for two purposes, as the plan specifies: seven days back for the hard weekly limits, twenty-eight
 * for the fairness terms. Loading only the service date would make every crew member look perfectly rested, and
 * the rest rules would never bind.
 *
 * <p>Depot membership comes from {@code crew_depot_history} rather than the current {@code depot_id}. A crew
 * member who transferred last week belonged to their old depot for last week's dates, and scheduling a past date
 * has to use the depot they were actually at.
 */
@Service
public class CrewSnapshotLoader {

    /** Rolling window for the hard weekly limits. */
    public static final int HARD_LIMIT_DAYS = 7;

    /** Rolling window for fairness, which is long enough that an uneven spread of night work is visible. */
    public static final int FAIRNESS_DAYS = 28;

    private final JdbcTemplate jdbc;
    private final AppTimeProperties timeProperties;

    public CrewSnapshotLoader(JdbcTemplate jdbc, AppTimeProperties timeProperties) {
        this.jdbc = jdbc;
        this.timeProperties = timeProperties;
    }

    @Transactional(readOnly = true)
    public Snapshot load(Long depotId, LocalDate serviceDate) {
        List<CrewView> members = loadMembers(depotId, serviceDate);
        CrewHistory history = loadHistory(members, serviceDate);
        return new Snapshot(new CrewPool(members), history);
    }

    private List<CrewView> loadMembers(Long depotId, LocalDate serviceDate) {
        ZoneId zone = timeProperties.zone();
        var dayStart = serviceDate.atStartOfDay(zone).toOffsetDateTime();

        Map<Long, Set<String>> qualifications = new HashMap<>();
        jdbc.query(
                """
                SELECT q.crew_member_id, q.code
                FROM crew_qualification q
                JOIN crew_member c ON c.id = q.crew_member_id
                WHERE c.depot_id = ? AND (q.valid_until IS NULL OR q.valid_until >= ?)
                """,
                rs -> {
                    qualifications
                            .computeIfAbsent(rs.getLong("crew_member_id"), key -> new HashSet<>())
                            .add(rs.getString("code"));
                },
                depotId,
                serviceDate);

        Map<Long, List<TimeWindow>> leave = new HashMap<>();
        jdbc.query(
                """
                SELECT l.crew_member_id,
                       EXTRACT(EPOCH FROM (l.starts_at - ?))::bigint AS from_sec,
                       EXTRACT(EPOCH FROM (l.ends_at   - ?))::bigint AS to_sec
                FROM crew_leave l
                JOIN crew_member c ON c.id = l.crew_member_id
                WHERE c.depot_id = ? AND l.period && tstzrange(?, ?, '[)')
                """,
                rs -> {
                    // Clamped into the service day: leave that began yesterday still covers this morning, and a
                    // negative window start would be refused by TimeWindow.
                    int from = (int) Math.max(0, rs.getLong("from_sec"));
                    int to = (int) Math.min(36 * 3600L, rs.getLong("to_sec"));
                    if (to > from) {
                        leave.computeIfAbsent(rs.getLong("crew_member_id"), key -> new ArrayList<>())
                                .add(new TimeWindow(from, to));
                    }
                },
                dayStart,
                dayStart,
                depotId,
                dayStart,
                dayStart.plusSeconds(36 * 3600L));

        return jdbc.query(
                """
                SELECT c.id, c.employee_code, c.crew_role, c.licence_class, c.licence_expiry,
                       c.weekly_off_dow, c.status,
                       COALESCE(h.depot_id, c.depot_id) AS effective_depot_id
                FROM crew_member c
                LEFT JOIN LATERAL (
                  SELECT depot_id FROM crew_depot_history
                  WHERE crew_member_id = c.id AND effective_from <= ?
                  ORDER BY effective_from DESC LIMIT 1) h ON TRUE
                WHERE c.depot_id = ? AND c.status <> 'TERMINATED'
                ORDER BY c.id
                """,
                (rs, row) -> new CrewView(
                        rs.getLong("id"),
                        rs.getString("employee_code"),
                        rs.getString("crew_role"),
                        rs.getLong("effective_depot_id"),
                        rs.getString("licence_class"),
                        rs.getDate("licence_expiry") == null
                                ? null
                                : rs.getDate("licence_expiry").toLocalDate(),
                        rs.getObject("weekly_off_dow") == null ? null : rs.getInt("weekly_off_dow"),
                        rs.getString("status"),
                        qualifications.getOrDefault(rs.getLong("id"), Set.of()),
                        leave.getOrDefault(rs.getLong("id"), List.of())),
                serviceDate,
                depotId);
    }

    /**
     * What these people worked over the fairness window.
     *
     * <p>One query for the whole pool rather than one per person: on the L dataset a depot has 285 crew, and 285
     * round trips per run is the kind of cost that only shows up in production.
     */
    private CrewHistory loadHistory(List<CrewView> members, LocalDate serviceDate) {
        CrewHistory history = new CrewHistory();
        if (members.isEmpty()) {
            return history;
        }
        String ids = members.stream()
                .map(member -> String.valueOf(member.id()))
                .reduce((a, b) -> a + "," + b)
                .map(joined -> "{" + joined + "}")
                .orElse("{}");

        jdbc.query(
                """
                SELECT a.crew_member_id, a.starts_at, a.ends_at,
                       d.spread_sec - d.break_sec AS work_sec,
                       (d.duty_type = 'NIGHT') AS night
                FROM duty_assignment a
                JOIN duty d ON d.id = a.duty_id
                WHERE a.status <> 'CANCELLED'
                  AND a.service_date >= ? AND a.service_date < ?
                  AND a.crew_member_id = ANY(CAST(? AS bigint[]))
                """,
                rs -> {
                    history.add(
                            rs.getLong("crew_member_id"),
                            rs.getTimestamp("starts_at").toInstant(),
                            rs.getTimestamp("ends_at").toInstant(),
                            rs.getInt("work_sec"),
                            rs.getBoolean("night"));
                },
                serviceDate.minusDays(FAIRNESS_DAYS),
                serviceDate,
                ids);

        return history;
    }

    public record Snapshot(CrewPool pool, CrewHistory history) {}
}
