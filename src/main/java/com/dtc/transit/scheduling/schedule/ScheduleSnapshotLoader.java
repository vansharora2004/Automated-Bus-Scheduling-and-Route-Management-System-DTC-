package com.dtc.transit.scheduling.schedule;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.StopView;
import com.dtc.transit.scheduling.engine.model.TimeWindow;
import com.dtc.transit.scheduling.engine.model.TravelTimes;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.timetable.calendar.ServiceCalendar;

/**
 * Loads everything a run needs, once, in one consistent read.
 *
 * <p>{@code REPEATABLE_READ} and read-only. The engine runs for seconds and asks millions of questions; if it
 * queried as it went, a bus could go into maintenance halfway through and the result would be a schedule that
 * was never valid at any single moment. One snapshot means the answer is valid as of one instant, which is
 * something a scheduler can reason about.
 *
 * <p>Travel times are pre-computed into maps rather than queried per lookup. A block build does on the order of
 * a million deadhead lookups; at even a tenth of a millisecond each that would be two minutes of database round
 * trips for data that fits comfortably in memory.
 */
@Service
public class ScheduleSnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSnapshotLoader.class);

    /**
     * Detour factor for a depot-to-stop estimate, matching {@code DeadheadMatrix}.
     *
     * <p>The depot is not a stop, so there is no surveyed deadhead to it. Rather than inventing a stop row, the
     * distance is measured in the projected CRS and converted with the same factor the deadhead matrix uses, so
     * pull-outs and ordinary dead running are costed on the same basis.
     */
    public static final double DEPOT_DETOUR_FACTOR = 1.3;

    public static final double DEPOT_SPEED_KMH = 25;

    /** Floor for a depot move, so a stop across the road does not come out as a zero-second pull-out. */
    public static final int MINIMUM_DEPOT_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final ServiceCalendar calendar;
    private final AppTimeProperties timeProperties;

    public ScheduleSnapshotLoader(
            JdbcTemplate jdbc, ServiceCalendar calendar, AppTimeProperties timeProperties) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.timeProperties = timeProperties;
    }

    /**
     * Reads one depot-day.
     *
     * @throws BusinessRuleException when the depot has no fleet, which would otherwise produce a schedule where
     *     every trip is uncovered and no explanation of why
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load(Long depotId, LocalDate serviceDate) {
        var dayType = calendar.resolve(serviceDate, depotId);

        List<TripView> trips = loadTrips(depotId, serviceDate, dayType.dayType().name());
        Map<Long, StopView> stops = loadStops(trips);
        List<BusView> buses = loadBuses(depotId, serviceDate);

        if (buses.isEmpty()) {
            throw new BusinessRuleException(
                    "DEPOT_HAS_NO_FLEET",
                    "Depot %d has no usable buses on %s, so nothing can be scheduled".formatted(depotId, serviceDate));
        }

        TravelTimes travelTimes = loadTravelTimes(depotId, stops.keySet());

        log.info(
                "snapshot for depot {} on {} ({}): {} trips, {} stops, {} buses",
                depotId,
                serviceDate,
                dayType.dayType(),
                trips.size(),
                stops.size(),
                buses.size());

        return new Snapshot(
                new DepotContext(depotId, serviceDate, null, stops, buses, travelTimes),
                trips,
                dayType.dayType().name(),
                dayType.overridden());
    }

    /**
     * Every trip the depot's active routes run on this day type.
     *
     * <p>Scoped by the route's owning depot. A trip belongs to a timetable, which belongs to a route, which
     * belongs to a depot: that chain is what makes a depot-day a self-contained scheduling problem.
     */
    private List<TripView> loadTrips(Long depotId, LocalDate serviceDate, String dayType) {
        return jdbc.query(
                """
                SELECT t.id, t.pattern_id, r.id AS route_id, t.start_stop_id, t.end_stop_id,
                       t.start_sec, t.end_sec, COALESCE(t.distance_m, 0) AS distance_m,
                       t.required_vehicle_class
                FROM trip t
                JOIN timetable tt ON tt.id = t.timetable_id
                JOIN route r ON r.id = tt.route_id
                WHERE r.depot_id = ?
                  AND tt.day_type = ?
                  AND tt.status = 'ACTIVE'
                  AND tt.validity @> CAST(? AS date)
                  AND r.status = 'ACTIVE'
                  AND t.start_stop_id IS NOT NULL
                  AND t.end_stop_id IS NOT NULL
                ORDER BY t.start_sec, t.id
                """,
                (rs, row) -> new TripView(
                        rs.getLong("id"),
                        rs.getLong("pattern_id"),
                        rs.getLong("route_id"),
                        rs.getLong("start_stop_id"),
                        rs.getLong("end_stop_id"),
                        rs.getInt("start_sec"),
                        rs.getInt("end_sec"),
                        rs.getDouble("distance_m"),
                        rs.getString("required_vehicle_class")),
                depotId,
                dayType,
                serviceDate);
    }

    /** Only the stops the trips actually touch, which is a small fraction of the network. */
    private Map<Long, StopView> loadStops(List<TripView> trips) {
        if (trips.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = trips.stream()
                .flatMap(trip -> java.util.stream.Stream.of(trip.startStopId(), trip.endStopId()))
                .distinct()
                .sorted()
                .toList();

        Map<Long, StopView> stops = new LinkedHashMap<>();
        jdbc.query(
                        """
                        SELECT id, is_terminal, is_relief_point, has_crew_facilities
                        FROM stop WHERE id = ANY(CAST(? AS bigint[]))
                        ORDER BY id
                        """,
                        (rs, row) -> new StopView(
                                rs.getLong("id"),
                                rs.getBoolean("is_terminal"),
                                rs.getBoolean("is_relief_point"),
                                rs.getBoolean("has_crew_facilities")),
                        asArrayLiteral(ids))
                .forEach(stop -> stops.put(stop.id(), stop));
        return stops;
    }

    /**
     * The depot's buses, with their unavailability converted to service-day seconds.
     *
     * <p>Retired buses are excluded; buses under maintenance are included with the window, because a bus in the
     * workshop until noon can still work the afternoon and excluding it outright would lose that capacity.
     */
    private List<BusView> loadBuses(Long depotId, LocalDate serviceDate) {
        ZoneId zone = timeProperties.zone();
        // An OffsetDateTime rather than an Instant: the driver maps this to timestamptz directly, where an
        // Instant has no SQL type it can infer and fails at bind time.
        var dayStart = serviceDate.atStartOfDay(zone).toOffsetDateTime();

        Map<Long, List<TimeWindow>> windows = new HashMap<>();
        jdbc.query(
                """
                SELECT u.bus_id,
                       EXTRACT(EPOCH FROM (u.starts_at - ?))::bigint AS from_sec,
                       EXTRACT(EPOCH FROM (u.ends_at   - ?))::bigint AS to_sec
                FROM bus_unavailability u
                JOIN bus b ON b.id = u.bus_id
                WHERE b.depot_id = ?
                  AND u.period && tstzrange(?, ?, '[)')
                ORDER BY u.bus_id, u.starts_at
                """,
                rs -> {
                    long busId = rs.getLong("bus_id");
                    // Clamped into the service day. An unavailability that started yesterday still blocks this
                    // morning, and a negative window start would be rejected by TimeWindow.
                    int from = (int) Math.max(0, rs.getLong("from_sec"));
                    int to = (int) Math.min(36 * 3600L, rs.getLong("to_sec"));
                    if (to > from) {
                        windows.computeIfAbsent(busId, key -> new ArrayList<>()).add(new TimeWindow(from, to));
                    }
                },
                dayStart,
                dayStart,
                depotId,
                dayStart,
                dayStart.plusSeconds(36 * 3600L));

        return jdbc.query(
                """
                SELECT id, fleet_no, bus_type, fuel_type, ev_range_km, capacity
                FROM bus
                WHERE depot_id = ? AND status IN ('ACTIVE', 'UNDER_MAINTENANCE')
                ORDER BY id
                """,
                (rs, row) -> new BusView(
                        rs.getLong("id"),
                        rs.getString("fleet_no"),
                        rs.getString("bus_type"),
                        rs.getString("fuel_type"),
                        (Integer) rs.getObject("ev_range_km"),
                        rs.getInt("capacity"),
                        windows.getOrDefault(rs.getLong("id"), List.of())),
                depotId);
    }

    /**
     * Pre-computes every stop-pair and depot-to-stop travel time the run can need.
     *
     * <p>Measured deadheads are read as-is. Pairs with no measured value get a straight-line estimate computed
     * in the projected CRS and flagged, which is the same rule {@code DeadheadMatrix} applies, so a run and an
     * ad-hoc lookup never disagree about the same pair.
     */
    private TravelTimes loadTravelTimes(Long depotId, java.util.Collection<Long> stopIds) {
        if (stopIds.isEmpty()) {
            return emptyTravelTimes();
        }
        List<Long> ids = stopIds.stream().sorted().toList();
        String idArray = asArrayLiteral(ids);

        Map<Long, int[]> measuredSeconds = new HashMap<>();
        Map<Long, double[]> measuredMetres = new HashMap<>();
        Map<Long, boolean[]> estimatedFlag = new HashMap<>();
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            indexOf.put(ids.get(i), i);
        }
        int n = ids.size();
        int[] seconds = new int[n * n];
        double[] metres = new double[n * n];
        boolean[] estimated = new boolean[n * n];

        // Straight-line distances first, as the baseline every pair gets.
        jdbc.query(
                """
                SELECT a.id AS from_id, b.id AS to_id, ST_Distance(a.location_utm, b.location_utm) AS metres
                FROM stop a, stop b
                WHERE a.id = ANY(CAST(? AS bigint[])) AND b.id = ANY(CAST(? AS bigint[]))
                """,
                rs -> {
                    int i = indexOf.get(rs.getLong("from_id"));
                    int j = indexOf.get(rs.getLong("to_id"));
                    double straight = rs.getDouble("metres");
                    double road = straight * DEPOT_DETOUR_FACTOR;
                    metres[i * n + j] = road;
                    seconds[i * n + j] = estimateSeconds(road);
                    estimated[i * n + j] = i != j;
                },
                idArray,
                idArray);

        // Measured values then overwrite the estimates where a survey exists. The latest band at or before
        // the whole day is used, because the engine's lookups are time-aware but the matrix here is not: a
        // per-band matrix would multiply memory by the band count for a second-order effect.
        jdbc.query(
                """
                SELECT DISTINCT ON (from_stop_id, to_stop_id)
                       from_stop_id, to_stop_id, travel_sec, COALESCE(distance_m, 0) AS distance_m, estimated
                FROM deadhead
                WHERE from_stop_id = ANY(CAST(? AS bigint[])) AND to_stop_id = ANY(CAST(? AS bigint[]))
                ORDER BY from_stop_id, to_stop_id, from_sec_band
                """,
                rs -> {
                    Integer i = indexOf.get(rs.getLong("from_stop_id"));
                    Integer j = indexOf.get(rs.getLong("to_stop_id"));
                    if (i == null || j == null) {
                        return;
                    }
                    seconds[i * n + j] = rs.getInt("travel_sec");
                    double stored = rs.getDouble("distance_m");
                    if (stored > 0) {
                        metres[i * n + j] = stored;
                    }
                    estimated[i * n + j] = rs.getBoolean("estimated");
                },
                idArray,
                idArray);

        Map<Long, Double> depotMetres = loadDepotDistances(depotId, idArray, ids);

        return new MatrixTravelTimes(indexOf, n, seconds, metres, estimated, depotMetres);
    }

    private Map<Long, Double> loadDepotDistances(Long depotId, String idArray, List<Long> ids) {
        Map<Long, Double> depotMetres = new HashMap<>();
        jdbc.query(
                """
                SELECT s.id, ST_Distance(ST_Transform(d.location, 32643), s.location_utm) AS metres
                FROM stop s, depot d
                WHERE d.id = ? AND s.id = ANY(CAST(? AS bigint[]))
                """,
                rs -> {
                    // A statement lambda, not an expression: Map.put returns a value, which would make the
                    // call ambiguous between a row handler and a result-set extractor.
                    depotMetres.put(rs.getLong("id"), rs.getDouble("metres") * DEPOT_DETOUR_FACTOR);
                },
                depotId,
                idArray);
        // Any stop the query missed falls back to a conservative figure rather than zero: a zero-length
        // pull-out would make every block look feasible from anywhere.
        ids.forEach(id -> depotMetres.putIfAbsent(id, 10_000.0));
        return depotMetres;
    }

    private static int estimateSeconds(double roadMetres) {
        return (int) Math.max(MINIMUM_DEPOT_SECONDS, Math.round(roadMetres / 1000.0 / DEPOT_SPEED_KMH * 3600));
    }

    private static String asArrayLiteral(List<Long> ids) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(ids.get(i));
        }
        return text.append('}').toString();
    }

    private static TravelTimes emptyTravelTimes() {
        return new TravelTimes() {
            @Override
            public int betweenStops(long fromStopId, long toStopId, int atSec) {
                return 0;
            }

            @Override
            public double distanceBetweenStops(long fromStopId, long toStopId) {
                return 0;
            }

            @Override
            public boolean isEstimated(long fromStopId, long toStopId) {
                return false;
            }

            @Override
            public int fromDepot(long toStopId) {
                return 0;
            }

            @Override
            public int toDepot(long fromStopId) {
                return 0;
            }

            @Override
            public double distanceFromDepot(long toStopId) {
                return 0;
            }

            @Override
            public double distanceToDepot(long fromStopId) {
                return 0;
            }
        };
    }

    /**
     * A flat-array travel time matrix.
     *
     * <p>Arrays rather than nested maps. At a few hundred stops this is a few hundred thousand primitives, which
     * is both smaller and an order of magnitude faster to read than boxed map lookups in the engine's hot loop.
     */
    private static final class MatrixTravelTimes implements TravelTimes {

        private final Map<Long, Integer> indexOf;
        private final int size;
        private final int[] seconds;
        private final double[] metres;
        private final boolean[] estimated;
        private final Map<Long, Double> depotMetres;

        private MatrixTravelTimes(
                Map<Long, Integer> indexOf,
                int size,
                int[] seconds,
                double[] metres,
                boolean[] estimated,
                Map<Long, Double> depotMetres) {
            this.indexOf = Map.copyOf(indexOf);
            this.size = size;
            this.seconds = seconds;
            this.metres = metres;
            this.estimated = estimated;
            this.depotMetres = Map.copyOf(depotMetres);
        }

        @Override
        public int betweenStops(long fromStopId, long toStopId, int atSec) {
            if (fromStopId == toStopId) {
                return 0;
            }
            Integer cell = cell(fromStopId, toStopId);
            // An unknown pair gets a deliberately pessimistic figure rather than zero. Zero would make an
            // impossible chain look feasible, which is the one failure mode a scheduler cannot see.
            return cell == null ? 3600 : seconds[cell];
        }

        @Override
        public double distanceBetweenStops(long fromStopId, long toStopId) {
            if (fromStopId == toStopId) {
                return 0;
            }
            Integer cell = cell(fromStopId, toStopId);
            return cell == null ? 20_000 : metres[cell];
        }

        @Override
        public boolean isEstimated(long fromStopId, long toStopId) {
            Integer cell = cell(fromStopId, toStopId);
            return cell == null || estimated[cell];
        }

        @Override
        public int fromDepot(long toStopId) {
            return estimateSeconds(distanceFromDepot(toStopId));
        }

        @Override
        public int toDepot(long fromStopId) {
            return estimateSeconds(distanceToDepot(fromStopId));
        }

        @Override
        public double distanceFromDepot(long toStopId) {
            return depotMetres.getOrDefault(toStopId, 10_000.0);
        }

        @Override
        public double distanceToDepot(long fromStopId) {
            return depotMetres.getOrDefault(fromStopId, 10_000.0);
        }

        private Integer cell(long fromStopId, long toStopId) {
            Integer i = indexOf.get(fromStopId);
            Integer j = indexOf.get(toStopId);
            return i == null || j == null ? null : i * size + j;
        }
    }

    /**
     * One depot-day's inputs.
     *
     * @param dayTypeOverridden whether a calendar exception decided the day type, which is recorded on the run
     *     so an unexpected timetable can be explained afterwards
     */
    public record Snapshot(DepotContext context, List<TripView> trips, String dayType, boolean dayTypeOverridden) {

        public Snapshot {
            trips = List.copyOf(trips);
        }
    }
}
