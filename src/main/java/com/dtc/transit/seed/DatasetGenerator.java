package com.dtc.transit.seed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.route.route.DayType;
import com.dtc.transit.route.route.Direction;
import com.dtc.transit.timetable.headway.HeadwayBand;
import com.dtc.transit.timetable.trip.TripGenerator;

/**
 * Builds the S, M and L datasets.
 *
 * <p>Deterministic from a fixed seed per size, and the determinism is checked rather than assumed: every
 * generated row contributes to a SHA-256 checksum before it is inserted, so two runs that differ anywhere
 * produce different checksums. The checksum deliberately covers natural keys and never database ids or
 * timestamps, which differ between runs for reasons that have nothing to do with the data.
 *
 * <p>Written against {@link JdbcTemplate} rather than the repositories. Inserting fifty thousand trips
 * through JPA would mean fifty thousand managed entities, and the generator is a bulk loader, not a domain
 * operation; going through services would also demand an authenticated actor, which a CLI loader has none of.
 *
 * <p>Trips come from the same {@link TripGenerator} the API uses. A separate generation path here would let
 * the dataset drift away from what the system produces, which is the one thing a reference dataset must not
 * do.
 */
@Component
public class DatasetGenerator {

    private static final Logger log = LoggerFactory.getLogger(DatasetGenerator.class);

    /** Roughly Connaught Place, the hub the radial routes converge on. */
    private static final double CENTRE_LON = 77.215;

    private static final double CENTRE_LAT = 28.632;

    /**
     * The service-area box, matching the polygon seeded in V5.
     *
     * <p>Every generated coordinate is clamped into it. A stop outside the service area would be rejected by
     * the Phase 4 geographic validation, so a dataset containing one would be unusable for the very tests it
     * exists to support.
     */
    private static final double MIN_LON = 76.82;

    private static final double MAX_LON = 77.63;

    private static final double MIN_LAT = 28.32;

    private static final double MAX_LAT = 28.93;

    /**
     * How far a radial route reaches, in degrees.
     *
     * <p>About 8 km at this latitude. Chosen so that a route takes roughly half an hour each way at the peak
     * speed below, which is what lets six buses cover sixty trips at a twenty-minute headway.
     */
    private static final double RADIAL_SPAN_DEG = 0.075;

    /** Angular span of a ring route, in radians, giving an arc of comparable length to a radial. */
    private static final double RING_SWEEP_RAD = 0.55;

    /** Service windows, in service-day seconds: morning peak, midday trough, evening peak. */
    private static final int[][] WINDOWS = {
        {6 * 3600, 10 * 3600, 1200}, {10 * 3600, 16 * 3600, 3600}, {16 * 3600, 20 * 3600, 1200}
    };

    /** Average speeds the running times are derived from, in km/h. */
    private static final double PEAK_SPEED_KMH = 16;

    private static final double OFF_PEAK_SPEED_KMH = 22;

    /** The date the generated unavailability and leave windows sit on, so they are reproducible. */
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 6, 1);

    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final TripGenerator tripGenerator;

    public DatasetGenerator(JdbcTemplate jdbc, TripGenerator tripGenerator) {
        this.jdbc = jdbc;
        this.tripGenerator = tripGenerator;
    }

    /**
     * Generates a dataset, optionally clearing what is there first.
     *
     * <p>One transaction. A half-loaded dataset is worse than none: it looks usable and quietly invalidates
     * every measurement taken against it.
     */
    @Transactional
    public DatasetReport generate(DatasetSize size, boolean purgeFirst) {
        if (purgeFirst) {
            purge();
        }

        long startedAt = System.nanoTime();
        Random random = new Random(size.seed());
        Checksum checksum = new Checksum();

        List<Depot> depots = generateDepots(size, random, checksum);
        List<Route> routes = generateRoutes(size, depots, random, checksum);
        int trips = generateTimetablesAndTrips(routes, checksum);
        int buses = generateBuses(size, depots, random, checksum);
        int crew = generateCrew(size, depots, random, checksum);
        int deadheads = generateDeadheads(depots, routes, checksum);

        DatasetReport report = new DatasetReport(
                size,
                depots.size(),
                size.routes(),
                size.stops(),
                size.routes(),
                trips,
                buses,
                crew,
                deadheads,
                checksum.hex(),
                (System.nanoTime() - startedAt) / 1_000_000);

        log.info("generated dataset {}", report);
        return report;
    }

    /**
     * Clears every table the generator writes, in foreign-key order.
     *
     * <p>{@code service_area} is left alone: it is reference data seeded by migration, and geometry validation
     * depends on it.
     */
    @Transactional
    public void purge() {
        for (String table : List.of(
                // Schedules first: they reference trips, stops and buses, and a dataset reload must not leave a
                // roster pointing at trips that no longer exist.
                "duty_assignment",
                "handover",
                "duty_piece",
                "duty",
                "piece_of_work",
                "conflict",
                "bus_assignment",
                "block_event",
                "vehicle_block",
                "trip",
                "headway_band",
                "timetable",
                "deadhead",
                "calendar_exception",
                "route_overlap",
                "pattern_stop",
                "running_time_band",
                "route_pattern",
                "route",
                "coverage_zone",
                "grid_cell",
                "crew_qualification",
                "crew_leave",
                "crew_depot_history",
                "crew_member",
                "bus_unavailability",
                "bus")) {
            jdbc.update("DELETE FROM " + table);
        }

        // schedule and schedule_run reference each other, so one side has to be broken before either can go.
        jdbc.update("UPDATE schedule_run SET schedule_id = NULL WHERE schedule_id IS NOT NULL");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM schedule_run");
        // Depot-scoped rule sets only. The global one is seeded by migration and every run resolves through it.
        jdbc.update("DELETE FROM rule_set WHERE depot_id IS NOT NULL");
        // app_user references depot, so any account bound to a depot must lose that binding before the
        // depots go. Deleting the accounts instead would lock the operator out of the system they just
        // loaded data into.
        jdbc.update("UPDATE app_user SET depot_id = NULL WHERE depot_id IS NOT NULL");
        jdbc.update("DELETE FROM stop");
        jdbc.update("DELETE FROM depot");
    }

    // ---------------------------------------------------------------------
    // Depots
    // ---------------------------------------------------------------------

    private List<Depot> generateDepots(DatasetSize size, Random random, Checksum checksum) {
        List<Long> ids = reserve("depot_seq", size.depots());
        List<Depot> depots = new ArrayList<>(size.depots());
        List<Object[]> rows = new ArrayList<>(size.depots());

        for (int i = 0; i < size.depots(); i++) {
            double angle = 2 * Math.PI * i / size.depots();
            // Depots sit on a ring around the centre, which is roughly how a radial network is organised
            // and gives the generated routes somewhere to run from.
            double lon = clampLon(CENTRE_LON + 0.22 * Math.cos(angle));
            double lat = clampLat(CENTRE_LAT + 0.20 * Math.sin(angle));
            String code = "DPT-%02d".formatted(i + 1);
            String name = "Depot %d".formatted(i + 1);
            int parking = 80 + random.nextInt(120);
            int chargingBays = random.nextInt(10);

            depots.add(new Depot(ids.get(i), code, lon, lat, i));
            rows.add(new Object[] {ids.get(i), code, name, lon, lat, parking, chargingBays});
            checksum.add("depot", code, name, coord(lon), coord(lat), parking, chargingBays);
        }

        batch(
                """
                INSERT INTO depot (id, code, name, location, parking_capacity, charging_bays)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)
                """,
                rows);
        return depots;
    }

    // ---------------------------------------------------------------------
    // Routes, patterns, stops and running times
    // ---------------------------------------------------------------------

    private List<Route> generateRoutes(DatasetSize size, List<Depot> depots, Random random, Checksum checksum) {
        int routeCount = size.routes();
        List<Long> routeIds = reserve("route_seq", routeCount);
        List<Long> patternIds = reserve("route_pattern_seq", routeCount * 2);
        List<Long> stopIds = reserve("stop_seq", routeCount * DatasetSize.STOPS_PER_ROUTE);

        List<Route> routes = new ArrayList<>(routeCount);
        List<Object[]> routeRows = new ArrayList<>();
        List<Object[]> patternRows = new ArrayList<>();
        List<Object[]> stopRows = new ArrayList<>();
        List<Object[]> patternStopRows = new ArrayList<>();
        List<Object[]> runningTimeRows = new ArrayList<>();

        for (int r = 0; r < routeCount; r++) {
            Depot depot = depots.get(r / size.routesPerDepot());
            int indexInDepot = r % size.routesPerDepot();
            boolean ring = indexInDepot % 3 == 2;

            List<Point> line = ring ? ringLine(depot, indexInDepot) : radialLine(depot, indexInDepot);
            double lengthM = lengthMetres(line);
            String routeNo = "%s%03d".formatted(ring ? "R" : "D", r + 1);
            String routeName = "%s to %s".formatted(depot.code(), ring ? "Ring" : "Centre");

            Long routeId = routeIds.get(r);
            routeRows.add(new Object[] {routeId, routeNo, routeName, depot.id(), REFERENCE_DATE});
            checksum.add("route", routeNo, routeName, depot.code(), ring ? "RING" : "RADIAL");

            // Stops sit on the line's vertices, so every stop is exactly on its route. Phase 4 warns about
            // stops more than 50 m off the line, and a dataset full of those warnings would be noise.
            List<Long> routeStopIds = new ArrayList<>(line.size());
            for (int s = 0; s < line.size(); s++) {
                Point point = line.get(s);
                Long stopId = stopIds.get(r * DatasetSize.STOPS_PER_ROUTE + s);
                boolean terminal = s == 0 || s == line.size() - 1;
                String code = "STP-%05d".formatted(r * DatasetSize.STOPS_PER_ROUTE + s + 1);
                String name = "%s stop %d".formatted(routeNo, s + 1);

                routeStopIds.add(stopId);
                // Terminals double as relief points with facilities: that is where a crew change can
                // realistically happen, and Phase 7 duty building needs somewhere to put breaks.
                stopRows.add(new Object[] {
                    stopId, code, name, point.lon(), point.lat(), terminal, terminal, terminal
                });
                checksum.add("stop", code, name, coord(point.lon()), coord(point.lat()), terminal);
            }

            int peakSec = runningSeconds(lengthM, PEAK_SPEED_KMH);
            int offPeakSec = runningSeconds(lengthM, OFF_PEAK_SPEED_KMH);

            for (Direction direction : List.of(Direction.UP, Direction.DOWN)) {
                boolean up = direction == Direction.UP;
                Long patternId = patternIds.get(r * 2 + (up ? 0 : 1));
                List<Point> directed = up ? line : line.reversed();
                List<Long> directedStops = up ? routeStopIds : routeStopIds.reversed();
                String wkt = wkt(directed);

                patternRows.add(new Object[] {patternId, routeId, direction.name(), wkt});
                checksum.add("pattern", routeNo, direction.name(), wkt);

                for (int s = 0; s < directedStops.size(); s++) {
                    patternStopRows.add(new Object[] {patternId, s + 1, directedStops.get(s)});
                }

                for (int[] window : WINDOWS) {
                    int runningSec = window[2] == 3600 ? offPeakSec : peakSec;
                    runningTimeRows.add(
                            new Object[] {patternId, DayType.WEEKDAY.name(), window[0], window[1], runningSec});
                    checksum.add(
                            "runningTime", routeNo, direction.name(), window[0], window[1], runningSec);
                }

                routes.add(new Route(routeId, patternId, routeNo, direction, depot, lengthM, directedStops));
            }
        }

        batch(
                """
                INSERT INTO route (id, route_no, name, depot_id, status, effective_from)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                routeRows);
        batch(
                """
                INSERT INTO stop (id, code, name, location, is_terminal, is_relief_point, has_crew_facilities)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?)
                """,
                stopRows);
        batch(
                """
                INSERT INTO route_pattern (id, route_id, direction, geom)
                VALUES (?, ?, ?, ST_GeomFromText(?, 4326))
                """,
                patternRows);
        batch("INSERT INTO pattern_stop (pattern_id, seq, stop_id) VALUES (?, ?, ?)", patternStopRows);
        batch(
                """
                INSERT INTO running_time_band (pattern_id, day_type, from_sec, to_sec, running_sec)
                VALUES (?, ?, ?, ?, ?)
                """,
                runningTimeRows);
        return routes;
    }

    /**
     * A radial route: a corridor running from its depot towards the centre.
     *
     * <p>Roughly 8 km, not all the way in. The length is what makes the dataset's four headline figures
     * consistent with each other: 60 trips a day at a 20-minute peak headway needs
     * {@code ceil(cycle / headway)} buses, and a route long enough to take an hour each way would need twelve
     * buses rather than the six the plan allocates. Scheduling such a dataset would leave a quarter of its
     * trips uncovered for arithmetic reasons that say nothing about the scheduler.
     */
    private List<Point> radialLine(Depot depot, int indexInDepot) {
        double spread = ((indexInDepot % 7) - 3) * 0.02;
        double startLon = depot.lon() + spread;
        double startLat = depot.lat() + spread * 0.6;

        // A unit step from the depot towards the centre, scaled to the target length rather than run all the
        // way in, so routes stay local to their depot and pull-outs stay short.
        double towardsCentreLon = CENTRE_LON - depot.lon();
        double towardsCentreLat = CENTRE_LAT - depot.lat();
        double magnitude = Math.hypot(towardsCentreLon, towardsCentreLat);
        double scale = magnitude == 0 ? 0 : RADIAL_SPAN_DEG / magnitude;

        double endLon = startLon + towardsCentreLon * scale;
        double endLat = startLat + towardsCentreLat * scale;
        return interpolate(startLon, startLat, endLon, endLat, indexInDepot);
    }

    /**
     * A ring route: an arc at constant radius, crossing the radials rather than running along them.
     *
     * <p>The arc is kept to about the same length as a radial route, for the same reason.
     */
    private List<Point> ringLine(Depot depot, int indexInDepot) {
        double radius = 0.13 + (indexInDepot % 4) * 0.02;
        double baseAngle = Math.atan2(depot.lat() - CENTRE_LAT, depot.lon() - CENTRE_LON);
        double halfSweep = RING_SWEEP_RAD / 2;
        List<Point> points = new ArrayList<>(DatasetSize.STOPS_PER_ROUTE);
        for (int i = 0; i < DatasetSize.STOPS_PER_ROUTE; i++) {
            double angle = baseAngle - halfSweep
                    + RING_SWEEP_RAD * i / (DatasetSize.STOPS_PER_ROUTE - 1.0);
            points.add(new Point(
                    clampLon(CENTRE_LON + radius * Math.cos(angle)),
                    clampLat(CENTRE_LAT + radius * 0.9 * Math.sin(angle))));
        }
        return points;
    }

    /**
     * Straight line between two points, with a fixed sideways kink.
     *
     * <p>The kink is derived from the route index rather than the random source. A straight two-point line
     * would make every radial route of a depot geometrically identical, and the overlap analysis would
     * correctly report the whole network as duplicated.
     */
    private List<Point> interpolate(double fromLon, double fromLat, double toLon, double toLat, int index) {
        List<Point> points = new ArrayList<>(DatasetSize.STOPS_PER_ROUTE);
        double kink = ((index % 5) - 2) * 0.012;
        for (int i = 0; i < DatasetSize.STOPS_PER_ROUTE; i++) {
            double t = i / (DatasetSize.STOPS_PER_ROUTE - 1.0);
            // A half-sine bulge: zero at both ends, widest in the middle, so the terminals stay put.
            double offset = kink * Math.sin(Math.PI * t);
            points.add(new Point(
                    clampLon(fromLon + (toLon - fromLon) * t + offset),
                    clampLat(fromLat + (toLat - fromLat) * t - offset * 0.5)));
        }
        return points;
    }

    // ---------------------------------------------------------------------
    // Timetables and trips
    // ---------------------------------------------------------------------

    private int generateTimetablesAndTrips(List<Route> routes, Checksum checksum) {
        // One timetable per route, keyed by route id: the two directions share it, as they do in reality.
        List<Long> routeIdsSeen = routes.stream().map(Route::routeId).distinct().toList();
        List<Long> timetableIds = reserve("timetable_seq", routeIdsSeen.size());

        List<Object[]> timetableRows = new ArrayList<>();
        List<Object[]> headwayRows = new ArrayList<>();
        List<Object[]> tripRows = new ArrayList<>();

        for (int i = 0; i < routeIdsSeen.size(); i++) {
            Long routeId = routeIdsSeen.get(i);
            Long timetableId = timetableIds.get(i);
            timetableRows.add(new Object[] {timetableId, routeId, DayType.WEEKDAY.name(), REFERENCE_DATE});

            for (Route route : routes) {
                if (!route.routeId().equals(routeId)) {
                    continue;
                }
                checksum.add("timetable", route.routeNo(), DayType.WEEKDAY.name(), REFERENCE_DATE.toString());

                List<HeadwayBand> bands = new ArrayList<>();
                for (int[] window : WINDOWS) {
                    bands.add(new HeadwayBand(
                            timetableId, route.direction(), window[0], window[1], window[2]));
                    headwayRows.add(new Object[] {
                        timetableId, route.direction().name(), window[0], window[1], window[2]
                    });
                    checksum.add(
                            "headway", route.routeNo(), route.direction().name(), window[0], window[1], window[2]);
                }

                var request = new TripGenerator.Request(
                        route.patternId(),
                        route.direction(),
                        DayType.WEEKDAY,
                        bands,
                        runningTimeBandsFor(route),
                        route.lengthM(),
                        null,
                        true);
                var result = tripGenerator.generate(request);

                Long startStop = route.stopIds().get(0);
                Long endStop = route.stopIds().get(route.stopIds().size() - 1);
                for (var draft : result.trips()) {
                    tripRows.add(new Object[] {
                        timetableId,
                        route.patternId(),
                        startStop,
                        endStop,
                        draft.startSec(),
                        draft.endSec(),
                        draft.distanceMetres()
                    });
                    checksum.add(
                            "trip", route.routeNo(), route.direction().name(), draft.startSec(), draft.endSec());
                }
            }
        }

        batch(
                """
                INSERT INTO timetable (id, route_id, day_type, valid_from, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                timetableRows);
        batch(
                """
                INSERT INTO headway_band (timetable_id, direction, from_sec, to_sec, headway_sec)
                VALUES (?, ?, ?, ?, ?)
                """,
                headwayRows);
        batch(
                """
                INSERT INTO trip (timetable_id, pattern_id, start_stop_id, end_stop_id,
                                  start_sec, end_sec, distance_m)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tripRows);
        return tripRows.size();
    }

    /** Rebuilt in memory rather than read back, so trip generation does not depend on the insert order. */
    private List<com.dtc.transit.route.pattern.RunningTimeBand> runningTimeBandsFor(Route route) {
        int peakSec = runningSeconds(route.lengthM(), PEAK_SPEED_KMH);
        int offPeakSec = runningSeconds(route.lengthM(), OFF_PEAK_SPEED_KMH);
        List<com.dtc.transit.route.pattern.RunningTimeBand> bands = new ArrayList<>(WINDOWS.length);
        for (int[] window : WINDOWS) {
            bands.add(new com.dtc.transit.route.pattern.RunningTimeBand(
                    route.patternId(),
                    DayType.WEEKDAY,
                    window[0],
                    window[1],
                    window[2] == 3600 ? offPeakSec : peakSec));
        }
        return bands;
    }

    // ---------------------------------------------------------------------
    // Fleet
    // ---------------------------------------------------------------------

    private int generateBuses(DatasetSize size, List<Depot> depots, Random random, Checksum checksum) {
        int total = size.buses();
        List<Long> busIds = reserve("bus_seq", total);
        List<Object[]> busRows = new ArrayList<>(total);
        List<Object[]> unavailabilityRows = new ArrayList<>();
        int perDepot = total / depots.size();

        for (int i = 0; i < total; i++) {
            Depot depot = depots.get(Math.min(i / perDepot, depots.size() - 1));
            Long busId = busIds.get(i);

            // Registration is globally unique; fleet number only within a depot, which is how depots
            // actually number their vehicles.
            String registration = "DL%02dPC%04d".formatted(1 + i % 13, i + 1);
            String fleetNo = "%s-%04d".formatted(depot.code(), i % perDepot + 1);

            boolean electric = i % 7 == 0;
            String fuel = electric ? "ELECTRIC" : (i % 3 == 0 ? "DIESEL" : "CNG");
            String busType = switch (i % 5) {
                case 0 -> "LOW_FLOOR";
                case 1 -> "MIDI";
                case 2 -> "ARTICULATED";
                default -> "STANDARD";
            };
            int capacity = switch (busType) {
                case "MIDI" -> 28;
                case "ARTICULATED" -> 65;
                default -> 42;
            };
            Integer evRange = electric ? 160 + random.nextInt(80) : null;
            boolean isAc = i % 4 == 0;

            // One bus in every twenty-five is out of service, and carries a maintenance window over the
            // morning peak. These are the deliberately infeasible pockets: a dataset where everything is
            // available never exercises conflict detection.
            boolean underMaintenance = i % 25 == 24;
            String status = underMaintenance ? "UNDER_MAINTENANCE" : "ACTIVE";

            busRows.add(new Object[] {
                busId, registration, registration, fleetNo, depot.id(), busType, fuel, isAc, capacity,
                evRange, status
            });
            checksum.add("bus", registration, fleetNo, depot.code(), busType, fuel, capacity, evRange, status);

            if (underMaintenance) {
                OffsetDateTime from = REFERENCE_DATE.atTime(4, 0).atOffset(ZoneOffset.UTC);
                unavailabilityRows.add(new Object[] {busId, from, from.plusHours(8), "MAINTENANCE"});
                checksum.add("busUnavailable", registration, from.toString());
            }
        }

        batch(
                """
                INSERT INTO bus (id, registration_no, registration_no_raw, fleet_no, depot_id, bus_type,
                                 fuel_type, is_ac, capacity, ev_range_km, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                busRows);
        batch(
                """
                INSERT INTO bus_unavailability (bus_id, starts_at, ends_at, reason)
                VALUES (?, ?, ?, ?)
                """,
                unavailabilityRows);
        return busRows.size();
    }

    // ---------------------------------------------------------------------
    // Crew
    // ---------------------------------------------------------------------

    private int generateCrew(DatasetSize size, List<Depot> depots, Random random, Checksum checksum) {
        int total = size.crew();
        List<Long> crewIds = reserve("crew_member_seq", total);
        List<Object[]> crewRows = new ArrayList<>(total);
        List<Object[]> historyRows = new ArrayList<>(total);
        List<Object[]> leaveRows = new ArrayList<>();
        List<Object[]> qualificationRows = new ArrayList<>();
        int perDepot = total / depots.size();

        for (int i = 0; i < total; i++) {
            Depot depot = depots.get(Math.min(i / perDepot, depots.size() - 1));
            Long crewId = crewIds.get(i);

            // Leading zeros are the point of the text column, so the generated codes carry them.
            String employeeCode = "%06d".formatted(100_000 + i);
            boolean driver = i % 5 < 3;
            String role = driver ? "DRIVER" : "CONDUCTOR";

            // Every fortieth driver's licence expires within the month, which is what the expiry report
            // and the Phase 7 assignment rules have to notice.
            boolean expiringSoon = driver && i % 40 == 7;
            LocalDate expiry = driver
                    ? (expiringSoon ? REFERENCE_DATE.plusDays(20) : REFERENCE_DATE.plusYears(2 + i % 4))
                    : null;
            String licenceNo = driver ? "DL-%07d".formatted(500_000 + i) : null;
            String licenceClass = driver ? (i % 3 == 0 ? "HPMV" : "HMV") : null;
            int weeklyOff = 1 + i % 7;

            crewRows.add(new Object[] {
                crewId, employeeCode, "Crew %d".formatted(i + 1), role, depot.id(), licenceNo, licenceClass,
                expiry, "ACTIVE", weeklyOff
            });
            historyRows.add(new Object[] {crewId, depot.id(), REFERENCE_DATE.minusYears(1)});
            checksum.add("crew", employeeCode, role, depot.code(), licenceClass, expiry, weeklyOff);

            // One in twenty is away across the morning peak, so relief cover is genuinely needed.
            if (i % 20 == 3) {
                OffsetDateTime from = REFERENCE_DATE.atTime(3, 0).atOffset(ZoneOffset.UTC);
                leaveRows.add(new Object[] {crewId, from, from.plusHours(10), "SICK"});
                checksum.add("crewLeave", employeeCode, from.toString());
            }

            // Electric buses need a trained driver, and there are fewer of those than there are EVs.
            if (driver && i % 9 == 0) {
                qualificationRows.add(new Object[] {crewId, "EV", REFERENCE_DATE.plusYears(3)});
                checksum.add("crewQualification", employeeCode, "EV");
            }
        }

        batch(
                """
                INSERT INTO crew_member (id, employee_code, name, crew_role, depot_id, licence_no,
                                         licence_class, licence_expiry, status, weekly_off_dow)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                crewRows);
        batch(
                """
                INSERT INTO crew_depot_history (crew_member_id, depot_id, effective_from) VALUES (?, ?, ?)
                """,
                historyRows);
        batch(
                """
                INSERT INTO crew_leave (crew_member_id, starts_at, ends_at, leave_type) VALUES (?, ?, ?, ?)
                """,
                leaveRows);
        batch(
                """
                INSERT INTO crew_qualification (crew_member_id, code, valid_until) VALUES (?, ?, ?)
                """,
                qualificationRows);
        return crewRows.size();
    }

    // ---------------------------------------------------------------------
    // Deadheads
    // ---------------------------------------------------------------------

    /**
     * Measured deadheads between the terminals a bus realistically moves between.
     *
     * <p>Only neighbouring terminals within a depot, not every pair. The full matrix for L would be over a
     * million rows to describe moves no block would ever make, and {@link
     * com.dtc.transit.timetable.deadhead.DeadheadMatrix} estimates whatever is missing, so the gaps are
     * covered rather than fatal.
     */
    private int generateDeadheads(List<Depot> depots, List<Route> routes, Checksum checksum) {
        List<Object[]> rows = new ArrayList<>();

        for (Depot depot : depots) {
            List<Route> upPatterns = routes.stream()
                    .filter(route -> route.depot().index() == depot.index() && route.direction() == Direction.UP)
                    .toList();
            if (upPatterns.size() < 2) {
                continue;
            }
            for (int i = 0; i < upPatterns.size(); i++) {
                Route from = upPatterns.get(i);
                Route to = upPatterns.get((i + 1) % upPatterns.size());
                Long fromStop = from.stopIds().get(from.stopIds().size() - 1);
                Long toStop = to.stopIds().get(0);
                if (fromStop.equals(toStop)) {
                    continue;
                }
                for (int band : new int[] {0, 6 * 3600, 10 * 3600, 16 * 3600}) {
                    // Peak bands are slower, which is the whole reason the matrix is banded.
                    boolean peak = band == 6 * 3600 || band == 16 * 3600;
                    int travelSec = peak ? 1800 : 1200;
                    rows.add(new Object[] {fromStop, toStop, band, travelSec, 8000.0, false});
                    checksum.add("deadhead", from.routeNo(), to.routeNo(), band, travelSec);
                }
            }
        }

        batch(
                """
                INSERT INTO deadhead (from_stop_id, to_stop_id, from_sec_band, travel_sec, distance_m, estimated)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                rows);
        return rows.size();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Takes a block of ids from a sequence in one round trip.
     *
     * <p>Ids are needed before insert so children can reference parents inside a single batch. Drawing them
     * from the real sequence, rather than counting from one, is what keeps the generator compatible with
     * Hibernate's allocator afterwards.
     */
    private List<Long> reserve(String sequence, int count) {
        if (count <= 0) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT nextval(CAST(? AS regclass)) FROM generate_series(1, ?)", Long.class, sequence, count);
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int start = 0; start < rows.size(); start += BATCH) {
            jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + BATCH, rows.size())));
        }
    }

    private static int runningSeconds(double lengthM, double speedKmh) {
        // Rounded to the minute, because a published running time of 4,037 seconds is not a real artefact.
        int seconds = (int) Math.round(lengthM / 1000.0 / speedKmh * 3600);
        return Math.max(300, Math.round(seconds / 60f) * 60);
    }

    /**
     * Length of a polyline in metres.
     *
     * <p>Computed locally rather than asked of PostGIS. The value is only needed to derive a plausible
     * running time, and a round trip per route would dominate the generator's runtime at L.
     */
    private static double lengthMetres(List<Point> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            double midLat = Math.toRadians((a.lat() + b.lat()) / 2);
            double dx = (b.lon() - a.lon()) * 111_320 * Math.cos(midLat);
            double dy = (b.lat() - a.lat()) * 110_574;
            total += Math.hypot(dx, dy);
        }
        return total;
    }

    private static String wkt(List<Point> points) {
        StringBuilder text = new StringBuilder("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(coord(points.get(i).lon())).append(' ').append(coord(points.get(i).lat()));
        }
        return text.append(')').toString();
    }

    /** Fixed six decimal places, so the checksum cannot change with the default locale or formatting. */
    private static String coord(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static double clampLon(double lon) {
        return Math.min(MAX_LON, Math.max(MIN_LON, lon));
    }

    private static double clampLat(double lat) {
        return Math.min(MAX_LAT, Math.max(MIN_LAT, lat));
    }

    private record Point(double lon, double lat) {}

    private record Depot(Long id, String code, double lon, double lat, int index) {}

    private record Route(
            Long routeId,
            Long patternId,
            String routeNo,
            Direction direction,
            Depot depot,
            double lengthM,
            List<Long> stopIds) {}

    /**
     * The running digest over everything generated.
     *
     * <p>Fed natural keys only. Including database ids would make the checksum change on every run, which
     * would turn the determinism check into noise and get it deleted.
     */
    private static final class Checksum {

        private final MessageDigest digest;

        Checksum() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by every Java platform", e);
            }
        }

        void add(Object... parts) {
            StringBuilder line = new StringBuilder();
            for (Object part : parts) {
                line.append(part).append('|');
            }
            line.append('\n');
            digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
        }

        String hex() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /**
     * What a generation run produced.
     *
     * @param checksum SHA-256 over the logical content, so two runs can be compared without comparing rows
     */
    public record DatasetReport(
            DatasetSize size,
            int depots,
            int routes,
            int stops,
            int timetables,
            int trips,
            int buses,
            int crew,
            int deadheads,
            String checksum,
            long millis) {}
}
