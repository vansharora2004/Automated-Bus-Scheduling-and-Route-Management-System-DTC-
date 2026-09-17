# Automated Bus Scheduling and Route Management System — Architecture

## 1. Architecture Overview

The application is a single Spring Boot service backed by PostgreSQL with PostGIS.

It is built as a modular monolith. There is one deployable unit, but the code is split into domain modules with enforced boundaries, and any module can be extracted later if that ever becomes necessary.

The high-level architecture is:

```text
                    +----------------------+
                    |      API clients     |
                    |  web app, GIS tool,  |
                    |       scripts        |
                    +----------+-----------+
                               |
                      HTTPS  JSON / GeoJSON
                               |
                               v
                    +----------------------+
                    |    Reverse proxy     |
                    | TLS, rate limiting   |
                    +----------+-----------+
                               |
              +----------------+----------------+
              |                                 |
              v                                 v
    +-------------------+             +-------------------+
    |  Spring Boot app  |             |  Spring Boot app  |
    |    instance 1     |    ...      |    instance N     |
    +---------+---------+             +---------+---------+
              |                                 |
              +----------------+----------------+
                               |
                      JDBC / Hibernate Spatial
                               |
                               v
                 +-----------------------------+
                 |     PostgreSQL 16           |
                 |  PostGIS 3.4, btree_gist    |
                 +-----------------------------+
```

Inside one application instance:

```text
Security filter chain
(JWT validation, RBAC)
         |
         v
REST controllers
(/api/v1)
         |
         v
Domain modules
         |
         +--------------------+
         |                    |
         v                    v
   Repositories          Run worker
         |                    |
         v                    v
    PostgreSQL        Scheduling engine
                       (pure Java)
```

API instances are stateless. Tokens are JWTs and no HTTP session is kept.

Run workers inside each instance claim queued scheduling runs from the `schedule_run` table using `SELECT ... FOR UPDATE SKIP LOCKED`. This works with one instance or with many, and it does not need a separate message broker.

---

# 2. Why a Modular Monolith

Three options were considered.

Microservices were rejected for the first version. Scheduling needs strongly consistent reads across master data, timetables and assignments. Splitting that across services would mean distributed transactions and duplicated data, which adds risk without any benefit at this team size.

A monolith with no internal boundaries was also rejected. The scheduling engine would slowly become entangled with JPA and web code, which makes it hard to test and hard to optimise.

A modular monolith was chosen. There is one deployable unit and ordinary ACID transactions. Module boundaries are enforced by package structure and by ArchUnit tests.

## Guiding principles

The engine is pure Java. Scheduling algorithms work on immutable in-memory records with no Spring, JPA or HTTP dependencies. Adapters load a snapshot and persist results. This keeps the engine deterministic, fast and unit-testable.

Constraints are data, not code branches. Labour and operational rules live in versioned rule sets so they can change without a redeploy.

The system explains rather than hides. When the engine cannot satisfy a constraint it emits a typed conflict with reasons. It never silently relaxes a hard rule.

The database is the last line of defence. Exclusion constraints, partial unique indexes and check constraints guarantee invariants even if application code has a bug or two requests race.

Geospatial work happens in PostGIS. Spatial indexes and set-based SQL do the geometry maths. Java handles validation and orchestration.

Everything is authenticated unless explicitly made public. Authorization is checked at the route level and again at method or data level.

---

# 3. System Context

```text
Depot Scheduler ---- generate, fix, override schedules ----+
                                                           |
HQ Planner ---- routes, stops, timetables, overlap --------+
                                                           |
Manager ---- approve, publish, reports --------------------+--> SYSTEM
                                                           |
Administrator ---- users, roles, rule sets ----------------+
                                                           |
GIS data sources ---- GeoJSON / shapefile import ----------+
                                                           |
Legacy spreadsheets ---- bulk CSV import ------------------+

SYSTEM ---- crew hours CSV ----> HR / payroll systems
SYSTEM ---- metrics, health ---> Prometheus / Grafana
```

---

# 4. Module Decomposition

The base package is `com.dtc.transit`.

```text
common
security
user
masterdata      (depot, bus, crew, stop)
route           (pattern, overlap, coverage, proposal)
timetable       (headway, trip, deadhead)
scheduling      (rules, vehicle, duty, assignment, conflict, run)
scheduling.engine   (pure Java)
reporting
audit
```

Dependencies run in one direction:

```text
security ----> user
masterdata --> common
route -------> masterdata
timetable ---> route
scheduling --> timetable, masterdata, scheduling.engine
reporting ---> scheduling, route
audit -------> common
```

## 4.1 What each module owns

`common` is the shared kernel. It holds the paging and filter framework, error handling, base entities, time utilities and geometry helpers.

```text
PageResponse
SortWhitelist
ProblemDetails
ServiceTime
GeoJsonCodec
```

`security` handles authentication, JWT issuing and validation, authorization helpers and depot scoping.

```text
SecurityConfig
TokenService
DepotAccessEvaluator
```

`user` holds users, roles, depot binding and token versioning.

`masterdata` holds depots, buses, availability windows, crew, leave, qualifications and stops.

```text
Depot
Bus
CrewMember
Stop
```

`route` holds routes, patterns as geometry, pattern stops, running-time bands, overlap analysis, coverage and the proposal workflow.

```text
Route
RoutePattern
OverlapAnalysisService
CoverageService
```

`timetable` holds timetables, headway bands, trip generation, the deadhead matrix and calendar exceptions.

```text
Timetable
Trip
TripGenerator
DeadheadMatrix
```

`scheduling` holds rule sets, runs, schedules, blocks, pieces, duties, assignments, conflicts, publishing and overrides. It also holds the adapters between the database and the engine.

```text
ScheduleRunService
ScheduleSnapshotLoader
SchedulePersister
PublishService
```

`scheduling.engine` holds the framework-free algorithms.

```text
BlockBuilder
DutyBuilder
CrewAssigner
ConstraintEngine
```

`reporting` holds KPIs, utilization, crew hours, the dashboard and CSV export.

`audit` holds the append-only audit log, written from domain events.

## 4.2 Boundary rules

These rules are enforced by ArchUnit tests, not by convention alone:

- `scheduling.engine` must not import `org.springframework..`, `jakarta.persistence..` or any other module's entities.
- Controllers never touch repositories directly. They go through services.
- Modules interact through service interfaces or domain events, never through another module's repositories.

## 4.3 Layering inside a module

```text
api/          -> controllers, request and response DTOs, validation
application/  -> services, transactions, orchestration, authorization
domain/       -> entities, value objects, domain events, invariants
infra/        -> repositories, native spatial queries, specifications
```

DTOs are mapped with MapStruct. Entities are never serialised directly, which avoids lazy-loading problems and mass-assignment vulnerabilities.

---

# 5. Data Architecture

## 5.1 Entity overview

```text
Depot
 |
 +--> Bus ------------> BusUnavailability
 |     |
 |     +--------------> BusAssignment
 |
 +--> CrewMember -----> CrewLeave
 |     |                CrewQualification
 |     |
 |     +--------------> DutyAssignment
 |
 +--> Route ----------> RoutePattern ---> PatternStop ---> Stop
 |                          |
 |                          +----------> RunningTimeBand
 |                          |
 |                          +----------> RouteOverlap
 |     |
 |     +--------------> Timetable ------> HeadwayBand
 |                          |
 |                          +----------> Trip
 |
 +--> ScheduleRun ----> Schedule
                          |
                          +--> VehicleBlock --> BlockEvent
                          |         |
                          |         +--------> PieceOfWork
                          |
                          +--> Duty ---------> DutyPiece
                          |      |
                          |      +----------> Handover
                          |
                          +--> Conflict
```

## 5.2 Core tables

Master data:

```text
depot               id, code (unique), name,
                    location geometry(Point,4326),
                    parking_capacity, charging_bays

bus                 id, registration_no (unique, normalised),
                    fleet_no, depot_id, bus_type, fuel_type,
                    is_ac, capacity, ev_range_km, status, version

bus_unavailability  bus_id, period tstzrange, reason
                    GiST index on (bus_id, period)

crew_member         id, employee_code (unique), name, crew_role,
                    depot_id, licence_no, licence_class,
                    licence_expiry, status, weekly_off_dow, version

crew_leave          crew_member_id, period tstzrange, leave_type

crew_qualification  crew_member_id, code, valid_until

stop                id, code, name,
                    location geometry(Point,4326),
                    is_terminal, is_relief_point, active
```

Depot history for crew is kept in `crew_depot_history`, so a crew member who transfers between depots keeps a correct history for past dates.

Network and timetable:

```text
route               id, route_no, name, depot_id, status,
                    effective_from, version
                    route_no is unique among ACTIVE routes
                    (partial index)

route_pattern       id, route_id, direction,
                    geom geometry(LineString,4326),
                    geom_utm geometry(LineString,32643),
                    length_m

pattern_stop        pattern_id, seq, stop_id, dist_from_start_m
                    primary key (pattern_id, seq)

running_time_band   pattern_id, day_type, from_sec, to_sec,
                    running_sec

timetable           id, route_id, day_type, valid_from, valid_to,
                    status

headway_band        timetable_id, direction, from_sec, to_sec,
                    headway_sec

trip                id, timetable_id, pattern_id, start_stop_id,
                    end_stop_id, start_sec, end_sec, distance_m,
                    required_vehicle_class

deadhead            from_stop_id, to_stop_id, from_sec_band,
                    travel_sec, distance_m, estimated

calendar_exception  service_date, depot_id (nullable),
                    day_type_override, note
```

`start_sec` and `end_sec` count from the service-day start and may exceed 86,400.

Scheduling:

```text
rule_set        id, name, depot_id (null means global),
                effective_from, rules jsonb, version

schedule_run    id uuid, depot_id, service_date, mode,
                rule_set_id, seed, status, claimed_by,
                heartbeat_at, progress, metrics jsonb,
                error, created_by

schedule        id, run_id, depot_id, service_date, version_no,
                status, needs_revalidation, published_at,
                published_by

vehicle_block   id, schedule_id, block_no, vehicle_class,
                pull_out_sec, pull_in_sec, service_km, dead_km

block_event     block_id, seq, type, trip_id, from_stop_id,
                to_stop_id, start_sec, end_sec,
                is_relief_opportunity

piece_of_work   id, block_id, from_event_seq, to_event_seq,
                start_sec, end_sec, start_relief_stop_id,
                end_relief_stop_id

duty            id, schedule_id, duty_no, mode, duty_type,
                sign_on_sec, sign_off_sec, platform_sec,
                paid_sec, break_sec, spread_sec, overtime_sec,
                version

duty_piece      duty_id, seq, piece_id (unique)

handover        id, schedule_id, block_id, relief_stop_id,
                at_sec, outgoing_duty_id, incoming_duty_id

duty_assignment id, duty_id, crew_role, crew_member_id,
                service_date, work_period tstzrange,
                schedule_status, status, override_reason, version

bus_assignment  id, block_id, bus_id, period tstzrange,
                schedule_status

conflict        id, schedule_id, type, severity,
                entity_refs jsonb, message, details jsonb,
                resolved, resolved_by
```

Block event types:

```text
PULL_OUT
TRIP
DEADHEAD
LAYOVER
DEPOT_PARK
CHARGING
PULL_IN
```

Analysis, users and audit:

```text
route_overlap   proposed_pattern_id, existing_pattern_id,
                overlap_m, overlap_ratio, shared_stops,
                same_direction, severity, computed_at

coverage_zone   id, name, zone_type,
                geom geometry(MultiPolygon,4326), geom_utm,
                population

app_user        id, username, password_hash, enabled, depot_id,
                token_version, failed_logins, locked_until

user_role       user_id, role

refresh_token   id, user_id, token_hash, expires_at, revoked,
                replaced_by

audit_log       id, at, actor, action, entity_type, entity_id,
                before jsonb, after jsonb, reason, trace_id
```

The audit log is append-only and partitioned by month.

## 5.3 Identifiers

Entities use `BIGINT` values from sequences with a pooled allocator, `allocationSize = 50`.

`IDENTITY` is deliberately avoided. It disables Hibernate JDBC batching, and a single scheduling run writes tens of thousands of rows.

`schedule_run` uses UUIDs, because its IDs are handed out as job handles.

## 5.4 Spatial storage

Two coordinate systems are used.

```text
EPSG:4326   interchange CRS (WGS84 lon/lat), matches GeoJSON
EPSG:32643  metric CRS (UTM zone 43N, covers Delhi NCR)
```

`geom` in 4326 is the source of truth. `geom_utm` in 32643 is a stored generated column, so indexed metric queries never have to call `ST_Transform` at query time.

Lengths, buffers and areas are always computed in metres, in the projected CRS.

Indexes are GiST, on `geom_utm` for patterns, stops and zones, and on `tstzrange` columns.

In Java, geometry is JTS (`org.locationtech.jts.geom`) through Hibernate Spatial. On the API, geometry is GeoJSON, read and written by a `GeoJsonCodec`.

Geometry validation checks `ST_IsValid`, at least two distinct points, and containment within the configured service-area polygon. Noisy GPS traces are simplified with `ST_SimplifyPreserveTopology` at roughly 5 m.

Example table:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE route_pattern (
  id         BIGINT PRIMARY KEY DEFAULT nextval('route_pattern_seq'),
  route_id   BIGINT NOT NULL REFERENCES route(id),
  direction  VARCHAR(8) NOT NULL CHECK (direction IN ('UP','DOWN','LOOP')),
  geom       geometry(LineString, 4326) NOT NULL,
  geom_utm   geometry(LineString, 32643)
             GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED,
  length_m   DOUBLE PRECISION
             GENERATED ALWAYS AS (ST_Length(ST_Transform(geom, 32643))) STORED,
  version    BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT route_pattern_geom_ok
      CHECK (ST_IsValid(geom) AND ST_NPoints(geom) >= 2),
  CONSTRAINT route_pattern_dir_uq UNIQUE (route_id, direction)
);

CREATE INDEX route_pattern_geom_utm_gix
  ON route_pattern USING GIST (geom_utm);
```

If the target PostGIS build rejects `ST_Transform` inside a generated column, `geom_utm` can be maintained by a `BEFORE INSERT OR UPDATE` trigger instead. The query design does not change.

## 5.5 Integrity guarantees in the database

Application checks can have bugs and requests can race. The important invariants are therefore also enforced by the database.

```sql
-- A crew member can never hold two overlapping PUBLISHED duties,
-- across dates and across depots.
ALTER TABLE duty_assignment
  ADD CONSTRAINT no_crew_double_booking
  EXCLUDE USING gist (crew_member_id WITH =, work_period WITH &&)
  WHERE (schedule_status = 'PUBLISHED' AND status <> 'CANCELLED');

-- A bus can never be in two overlapping PUBLISHED blocks.
ALTER TABLE bus_assignment
  ADD CONSTRAINT no_bus_double_booking
  EXCLUDE USING gist (bus_id WITH =, period WITH &&)
  WHERE (schedule_status = 'PUBLISHED');

-- Only one queued or running run per depot and service date.
CREATE UNIQUE INDEX one_active_run_per_depot_day
  ON schedule_run (depot_id, service_date)
  WHERE status IN ('QUEUED', 'RUNNING');

-- Only one published schedule per depot and service date.
CREATE UNIQUE INDEX one_published_schedule_per_depot_day
  ON schedule (depot_id, service_date)
  WHERE status = 'PUBLISHED';

-- A piece of work is used by at most one duty.
ALTER TABLE duty_piece
  ADD CONSTRAINT duty_piece_once UNIQUE (piece_id);
```

The exclusion constraints apply only to `PUBLISHED` rows for a reason. Drafts legitimately overlap with the currently published version while a replacement is being prepared, so drafts are validated in the application instead.

The publish transaction first flips the old version's rows to `SUPERSEDED`, then flips the new version's rows to `PUBLISHED`. If any cross-schedule overlap slipped through, for example the previous day's late duty overlapping this day's early duty, the constraint aborts the publish.

`work_period` is an absolute `tstzrange`, computed as the service date at 00:00 IST plus `sign_on_sec` through `sign_off_sec`. Storing it this way makes overlaps across midnight and across service dates detectable.

## 5.6 Time model

```text
Trip and duty times   INT seconds from service-day start
                      (GTFS style: 25:30:00 = 91,800 s)

Absolute instants     timestamptz, stored in UTC

Presentation          IST (Asia/Kolkata, UTC+05:30, no DST)

Service-day boundary  configurable, default 03:00
```

`hibernate.jdbc.time_zone` is set to UTC.

Service code never calls `LocalDateTime.now()` directly. An injected `Clock` is used everywhere, which keeps tests deterministic.

A trip starting at 00:40 belongs to the previous service date.

## 5.7 Volume and partitioning

These are sizing assumptions, not measurements.

```text
trip             ~50,000 per day type (not per date)
block_event      ~120,000 per published date
duty_assignment  ~25,000 per date
audit_log        10,000 to 100,000 per day
```

`block_event`, `duty_assignment` and `audit_log` are range-partitioned by month. Audit partitions are archived after a retention period.

---

# 6. Scheduling Engine

## 6.1 Pipeline

```text
Snapshot loader
(trips, deadheads, buses, availability,
 crew, leave, 7-day history, rule set)
        |
        v
1. BlockBuilder            trips -> blocks
        |
        v
2. ReliefOpportunityFinder mark relief events
        |
        v
   +----+----+
   |  mode   |
   +----+----+
        |
   LINKED         UNLINKED
        |             |
        v             v
3a. LinkedDuty    3b. PieceCutter     blocks -> pieces
    Builder            |
        |              v
        |         UnlinkedDutyBuilder pieces -> duties
        |              |
        |              v
        |         LocalSearchImprover
        |              |
        +------+-------+
               |
               v
4. CrewAssigner            duties -> named crew
               |
               v
5. BusAssigner             blocks -> physical buses
               |
               v
6. ConstraintEngine        full independent validation
               |
               v
        ScheduleResult
(blocks, duties, handovers, assignments,
 conflicts, metrics)
```

Every stage is an interface, and the implementation is chosen by configuration. That is what allows an alternative block builder today and a solver-based duty builder later.

```java
public interface BlockBuilder {
    VehicleSchedule build(List<TripView> trips, DepotContext ctx, RuleSet rules);
}

public interface DutyBuilder {
    CrewSchedule build(VehicleSchedule vs, DepotContext ctx, RuleSet rules);
}

public interface CrewAssigner {
    Roster assign(CrewSchedule cs, CrewPool pool, CrewHistory history, RuleSet rules);
}

public interface ConstraintEngine {
    List<Violation> validate(ScheduleResult result, DepotContext ctx, RuleSet rules);
}
```

## 6.2 Stage 1 — vehicle scheduling

The goal is to cover all trips with the fewest buses and the least dead running, while respecting vehicle class, minimum layover, deadhead time, EV range and bus availability.

The default implementation is `GreedyBestFitBlockBuilder`:

1. Sort trips by start time, then by id.
2. For each trip, find open blocks whose vehicle class satisfies the trip and which can physically reach the trip's start stop in time.
3. Among feasible blocks, choose the one with the smallest slack. Best fit keeps idle gaps small. Check EV cumulative km against usable range, and check maximum block duration.
4. If the slack exceeds the mid-day depot return threshold, insert pull-in, depot park and pull-out events, plus a charging event for electric buses.
5. If no block is feasible and a vehicle of the required class is still available, open a new block. Otherwise record an uncovered trip with a reason.

The feasibility test in step 2 is:

```text
block.end
  + minLayover(block.lastTrip)
  + deadhead(block.endStop -> trip.startStop)
<= trip.start
```

Complexity is O(T x B) per depot, which for roughly 1,500 trips and 150 blocks runs well under a second.

An optional `MinFleetMatchingBlockBuilder` exists as an alternative. It builds a compatibility graph where an edge from trip i to trip j means j can follow i, with edges pruned to a time window. The minimum fleet is then the trip count minus the maximum bipartite matching, computed with Hopcroft-Karp in O(E sqrt(V)). This gives the optimal peak vehicle requirement under the model, and it also serves as a lower bound in the evaluation.

## 6.3 Stage 2 — relief opportunities

A block event is a relief opportunity when it ends at a stop flagged as a relief point, or at the depot, and the arrival leaves at least the handover buffer before the next departure.

Pull-out and pull-in are always relief opportunities.

## 6.4 Stage 3a — linked duties

In linked mode the crew stays with one bus.

```text
for each block:
    reliefs = relief opportunities in time order
              (includes pull-out and pull-in)
    cursor = 0

    while cursor < last(reliefs):
        feasible = { j > cursor :
                     segment(cursor, j) satisfies all hard duty rules }

        if feasible is empty:
            emit duty(cursor, cursor+1)
                 with HARD conflict NO_FEASIBLE_RELIEF
            cursor = cursor + 1
        else:
            j* = argmin over feasible of
                   |work(cursor, j) - targetWork|
                   + penalty if remainder(j, last) < minPaidDuty
            emit linked duty(cursor, j*)
            record handover at reliefs[j*] if j* < last
            cursor = j*
```

The penalty term exists to avoid leaving a tiny unusable tail duty at the end of the block.

The break rule is that within the segment, a layover of at least the minimum break must occur before continuous work exceeds the maximum. If no such layover exists, the segment is infeasible.

A split linked duty is possible when the bus parks at the depot mid-day. The same crew may cover both the morning and evening portions of that bus, provided spread-over allows it.

One subtlety matters here. Hard constraints are not monotone in segment length, because a long layover later in the block can satisfy a break rule that a shorter segment failed. All candidate end points are therefore checked, rather than stopping at the first failure.

## 6.5 Stage 3b — unlinked duties

In unlinked mode the crew may change buses at relief points.

Piece cutting is done with dynamic programming per block. Cut points are chosen among relief opportunities so that each piece length falls within the allowed range, minimising the number of pieces and preferring cuts at the depot or at major relief points. Complexity is O(R squared) per block.

Duty construction is greedy:

```text
unassigned = all pieces sorted by start

while unassigned not empty:
    duty = new Duty(first(unassigned))

    loop:
        candidates = { q in unassigned :
            q.start >= duty.end
                       + transfer(duty.endRelief -> q.startRelief)
                       + handoverBuffer
            and rules.hardOk(duty + q) }

        if candidates empty:
            break

        q = argmin cost(duty, q)
        duty.add(q)

    finalize(duty)
```

The hard check covers work time, spread-over, whether a break is still owed, maximum pieces and maximum changeovers.

The candidate cost prefers a small idle gap, the same relief point, and a gap long enough to serve as a break when a break is still owed.

Finalizing a duty adds sign-on and sign-off, travel to and from the depot, and classifies the duty type.

Local search then improves the result. It is time-bounded and uses a seeded `SplittableRandom`.

```text
MovePiece    move a piece from duty A to duty B
SwapPieces   exchange pieces between two duties
MergeDuties  merge two short duties into one when feasible
SplitDuty    split an over-long duty to remove overtime
ReCut        shift a block cut point to a neighbouring relief
```

A move is accepted when all hard constraints still hold and the cost decreases. Simulated-annealing acceptance is available as an option. The search stops at the time budget, or after a configured number of non-improving iterations.

The cost function, with weights taken from the rule set:

```text
cost = w_duty     * number of duties
     + w_paid     * sum of paid time
     + w_idle     * sum of (paid - platform - paid breaks - signOnOff)
     + w_overtime * sum of overtime
     + w_split    * number of split duties
     + w_change   * sum of bus changeovers
     + w_soft     * sum of soft violation penalties
```

A later version could replace or augment `UnlinkedDutyBuilder` with a set-partitioning model solved by column generation. The interface already allows that swap without touching the rest of the pipeline.

## 6.6 Stage 4 — crew assignment

1. Build slots. One slot per duty per required role, meaning a driver and a conductor where one is needed.
2. Order slots by difficulty, fewest eligible candidates first, with earlier sign-on breaking ties. Candidate counts are recomputed after every N bookings.
3. Apply the hard eligibility filter. Every rejection records a reason code.
4. Score the eligible candidates. Lower is better.
5. Book the best candidate and update their in-memory history.
6. Place unused eligible crew into standby duties, up to the standby pool percentage.

Rejection reason codes:

```text
WRONG_DEPOT
WRONG_ROLE
INACTIVE
ON_LEAVE
WEEKLY_OFF
LICENCE_INVALID
QUALIFICATION_MISSING
INSUFFICIENT_REST
WEEKLY_HOURS_EXCEEDED
WEEKLY_REST_MISSING
ALREADY_BOOKED
```

The scoring function:

```text
score = a * (weeklyHours / weeklyMax)
      + b * sameShiftTypeStreak
      + c * nightDutiesLast28d
      - d * pairContinuity
      - e * seniorityPreference
```

If nobody is eligible, the duty is left unassigned and an `UNASSIGNED_DUTY` conflict carries an aggregated reason histogram, for example "18 drivers: 9 INSUFFICIENT_REST, 6 ON_LEAVE, 3 LICENCE_INVALID". That histogram is what makes the result actionable for a scheduler.

## 6.7 Constraint engine and rule sets

Constraints implement one interface and are registered in a catalogue:

```java
public interface Constraint<T> {
    String code();          // e.g. "MAX_CONTINUOUS_WORK"
    Severity severity();    // HARD or SOFT
    Scope scope();          // BLOCK, DUTY, ASSIGNMENT, SCHEDULE
    List<Violation> check(T subject, ValidationContext ctx);
}
```

The same catalogue is used twice. During construction it runs as fast incremental checks. During final validation it runs as a full re-check from scratch. The evaluation adds a third, independent SQL invariant suite that shares no code with either.

The default rule set is below. Every value is configurable, and the statutory values must be verified against the rules currently in force.

```text
maxWorkPerDutyMin          480    HARD   <= 8 h/day
maxContinuousWorkMin       300    HARD   <= 5 h before a rest interval
minBreakMin                 30    HARD   rest interval >= 30 min
maxSpreadOverMin           720    HARD   spread-over <= 12 h
maxWeeklyWorkMin          2880    HARD   <= 48 h per rolling 7 days
weeklyRestDays          1 per 7   HARD   weekly rest day
minRestBetweenDutiesMin    600    HARD   assumption, confirm with DTC
signOnMin / signOffMin   15 / 10  param  assumption
minLayoverMin      max(5, 10%)    HARD   operational, assumption
handoverBufferMin            5    HARD   operational, assumption
maxPiecesPerDuty             3    SOFT   assumption
maxBusChangeoversPerDuty     2    SOFT   assumption
targetWorkPerDutyMin       450    SOFT   efficiency target
minPaidDutyMin             240    SOFT   minimum paid guarantee
allowOvertime            false    policy
maxOvertimeMin              60    HARD when overtime is enabled
midDayDepotReturnGapMin     90    param  assumption
evRangeReservePct           15    HARD   battery safety margin
standbyPoolPct               5    param  assumption
serviceDayStart          03:00    param
```

The rule set is stored as `jsonb` and bound to a typed Java record with Bean Validation.

The rule set that applies is the one whose `effective_from` is the latest date on or before the service date, taking a depot-specific rule set first and falling back to the global one.

## 6.8 Conflict catalogue

Hard conflicts block publication:

```text
UNCOVERED_TRIP            a trip is in no block
BUS_DOUBLE_BOOKED         overlapping bus assignments
BUS_UNAVAILABLE           block assigned to a bus in maintenance
EV_RANGE_EXCEEDED         block km over usable range, no charging
NO_FEASIBLE_RELIEF        work between reliefs exceeds the limit
MAX_WORK_EXCEEDED         duty work over maximum
CONTINUOUS_WORK_EXCEEDED  no qualifying break in time
SPREAD_OVER_EXCEEDED      spread-over over maximum
HANDOVER_INFEASIBLE       gap < transfer + buffer, or wrong place
CREW_DOUBLE_BOOKED        overlapping crew work periods
INSUFFICIENT_REST         rest between duties below minimum
WEEKLY_HOURS_EXCEEDED     rolling 7-day work over maximum
WEEKLY_REST_MISSING       no rest day in the rolling 7 days
LICENCE_INVALID           licence expired or wrong class
CREW_ON_LEAVE             assigned during leave
QUALIFICATION_MISSING     e.g. an EV duty without EV training
UNASSIGNED_DUTY           no eligible crew
```

Soft conflicts are warnings and do not block publication:

```text
TOO_MANY_CHANGEOVERS      changeovers over maximum
SHORT_DUTY                paid time below the minimum guarantee
FAIRNESS_IMBALANCE        hours or night duties skewed
ESTIMATED_DEADHEAD        deadhead time estimated, not measured
```

## 6.9 Run execution

```text
Scheduler
   |
   | POST /api/v1/schedule-runs
   v
ScheduleRunController
   |
   v
ScheduleRunService
   |
   | INSERT schedule_run, status QUEUED
   | (partial unique index rejects duplicates)
   v
202 Accepted, Location: /schedule-runs/{id}

           ... meanwhile ...

RunWorker (every 2 s)
   |
   | claim oldest QUEUED run
   | FOR UPDATE SKIP LOCKED, set RUNNING
   v
Load snapshot (read-only, REPEATABLE READ)
   |
   v
Engine: blocks, duties, assignments, validation
   |
   v
Batch insert DRAFT schedule, set run COMPLETED
(one transaction)

Client subscribes to
GET /api/v1/schedule-runs/{id}/events   (SSE)
   -> progress events, then COMPLETED with scheduleId
```

Workers update `heartbeat_at` every 10 seconds. A reaper marks a run as `FAILED` with reason `WORKER_LOST` when its heartbeat is older than 2 minutes.

Everything a run produces is written in one transaction, so a crash leaves no partial schedule behind.

A bounded executor processes depot-days concurrently, with a pool size of cores minus one. The engine itself is CPU-bound and single-threaded per run.

Determinism comes from storing the seed on the run and from iterating collections in a stable order. Sorted lists are used, never `HashMap` iteration order.

## 6.10 Schedule lifecycle

```text
run completed
     |
     v
   DRAFT  <---- manual override (optimistic lock)
     |
     | validate with 0 HARD conflicts
     v
 VALIDATED ---- any edit ----> DRAFT
     |
     | publish (MANAGER)
     v
 PUBLISHED ---- newer version published ----> SUPERSEDED

DRAFT ---- discard ----> DISCARDED
```

A published schedule is immutable.

If master data changes afterwards, for example a bus breaks down, leave is approved or a licence expires, a revalidation job sets `needs_revalidation` and records the new conflicts. Resolving them requires a new version, which can be created as a copy of the published version and then edited.

---

# 7. Route Management and Geospatial Design

## 7.1 Proposal lifecycle

```text
planner creates route with patterns
     |
     v
 PROPOSED
     |
     | submit (overlap + coverage analysis attached)
     v
UNDER_REVIEW ---- returned for changes ----> PROPOSED
     |
     +---- manager rejects (reason) ----> REJECTED
     |
     | manager approves
     v
 APPROVED
     |
     | effective date reached
     v
  ACTIVE ---- withdrawn ----> RETIRED
```

## 7.2 Overlap detection

The overlap between a proposed pattern P and an existing pattern E is the length of P that lies within a buffer distance of E, counting only contiguous segments longer than a minimum length.

```text
bufferM       default 25 m
minSegmentM   default 200 m
```

Short segments are discarded on purpose, because they are almost always junction crossings rather than genuine shared corridor.

```sql
WITH proposed AS (
  SELECT ST_Transform(
           ST_SetSRID(ST_GeomFromGeoJSON(:geojson), 4326), 32643) AS g
),
candidates AS (                       -- index-assisted prefilter
  SELECT rp.id, rp.route_id, rp.direction, rp.geom_utm
  FROM route_pattern rp, proposed p
  WHERE ST_DWithin(rp.geom_utm, p.g, :bufferM)
    AND rp.id <> COALESCE(:excludePatternId, -1)
),
segments AS (
  SELECT c.id AS pattern_id, c.route_id, c.direction, d.geom AS seg
  FROM candidates c, proposed p,
       LATERAL ST_Dump(
         ST_Intersection(
           p.g,
           ST_Buffer(c.geom_utm, :bufferM, 'endcap=flat join=round')
         )
       ) d
)
SELECT s.pattern_id,
       r.route_no,
       s.direction,
       SUM(ST_Length(s.seg))                                       AS overlap_m,
       SUM(ST_Length(s.seg)) / (SELECT ST_Length(g) FROM proposed) AS overlap_ratio
FROM segments s
JOIN route r ON r.id = s.route_id AND r.status = 'ACTIVE'
WHERE ST_Length(s.seg) >= :minSegmentM
GROUP BY s.pattern_id, r.route_no, s.direction
ORDER BY overlap_m DESC;
```

The SQL result is then enriched in Java.

Shared stops are proposed stops within 30 m of a stop on the existing pattern.

Direction agreement compares the `ST_LineLocatePoint` ordering of the segment endpoints on both lines. This is what separates genuine same-direction duplication from the opposite direction on the same corridor, which is normal and expected.

Severity uses configurable thresholds:

```text
HIGH     overlap ratio >= 0.60
MEDIUM   0.30 to 0.60
LOW      below 0.30
```

The union of all overlapping segments is returned as GeoJSON, so a client can draw the shared corridor on a map.

Proposed geometries are normalised with `ST_LineMerge(ST_UnaryUnion(g))` before length is computed. Without this, a route that goes out and back along the same road would be double counted.

## 7.3 Coverage analysis

Each active stop is buffered by a catchment radius, default 500 m, in the metric CRS.

Zones are stored as grid cells, for example 250 m squares, alongside administrative wards. A materialized view holds per-cell coverage:

```text
cell_coverage(cell_id, covered boolean, nearest_stop_m)
```

It is refreshed with `REFRESH MATERIALIZED VIEW CONCURRENTLY` whenever stops change. This is what avoids intersecting one enormous unioned polygon on every request.

Zone coverage is the covered area of the zone divided by its total area, weighted by population where population data exists.

The coverage gain of a proposal is the set of cells newly covered by the proposed pattern's stops that are not covered today, reported as area and, where available, as population.

```sql
SELECT z.id, z.name, z.population,
       SUM(ST_Area(ST_Intersection(c.geom_utm, z.geom_utm)))
         FILTER (WHERE cc.covered) / ST_Area(z.geom_utm) AS covered_ratio
FROM coverage_zone z
JOIN grid_cell c      ON ST_Intersects(c.geom_utm, z.geom_utm)
JOIN cell_coverage cc ON cc.cell_id = c.id
WHERE z.zone_type = 'WARD'
GROUP BY z.id
HAVING SUM(ST_Area(ST_Intersection(c.geom_utm, z.geom_utm)))
         FILTER (WHERE cc.covered) / ST_Area(z.geom_utm) < :maxRatio
ORDER BY covered_ratio;
```

## 7.4 Hibernate Spatial usage

Entities map JTS types directly:

```java
@Column(columnDefinition = "geometry(LineString,4326)")
private LineString geom;
```

Simple spatial filters such as bounding box or near-point use HQL spatial functions inside Spring Data specifications or `@Query`.

Complex analytics such as overlap and coverage use native SQL with interface projections. That SQL lives in `infra/` and is covered by Testcontainers integration tests, because it is the part most likely to break silently.

---

# 8. Security Architecture

## 8.1 Authentication

```text
Client
  |
  | POST /api/v1/auth/login {username, password}
  v
AuthController
  |
  v
UserService
  | verify password (BCrypt/Argon2)
  | check lockout
  v
TokenService
  | issue access JWT   (15 min)
  | issue refresh token (7 days, hashed in DB)
  v
{accessToken, refreshToken, expiresIn}
```

Refresh:

```text
Client
  |
  | POST /api/v1/auth/refresh {refreshToken}
  v
TokenService
  | revoke old token
  | issue new pair
  | detect reuse
  v
new token pair
```

The application acts as its own resource server. `spring-boot-starter-oauth2-resource-server` validates self-issued RS256 JWTs through Nimbus, and keys are rotated using the `kid` header.

Token claims:

```text
sub    subject (user id)
roles  e.g. ["SCHEDULER"]
depot  depot id, nullable
tv     token version
iat, exp, jti
```

Revocation works through the token version. Disabling a user or changing their roles increments `token_version`. A lightweight filter compares the `tv` claim against a cached value, using Caffeine with a 60-second TTL, so the change takes effect within minutes and never later than the access-token lifetime.

Refresh-token reuse revokes the entire token family.

Brute-force protection uses per-IP and per-username rate limiting through Bucket4j, with lockout after 5 failures for 15 minutes.

Passwords use a `DelegatingPasswordEncoder` with Argon2 or BCrypt, a minimum length, and optionally a breached-password check.

## 8.2 Authorization model

Authorization is enforced at three levels, because any one of them can be bypassed by a future mistake.

```text
1. URL level
   SecurityFilterChain, coarse, deny by default

2. Method level
   @PreAuthorize on application services, also protects
   non-HTTP entry points

3. Data level
   Depot scope derived from the JWT, applied to every
   query specification
```

For single-entity access, `@depotAccess.canAccess(...)` is used.

Entities outside the caller's scope return 404, not 403. Returning 403 would confirm that the id exists, which lets an attacker enumerate ids.

## 8.3 Permission matrix

Full means create and edit, Read means read-only, and a dash means no access.

| Capability | ADMIN | MANAGER | PLANNER | SCHEDULER |
|---|---|---|---|---|
| Manage users and roles | Full | - | - | - |
| Edit rule sets | Full | Read | Read | Read |
| Create/edit depots | Full | Read | Read | Read |
| Create/edit buses | Full | Full (own depot) | Read | Read (own depot) |
| Change bus status | Full | Full | - | Full (own depot) |
| Create/edit crew | Full | Full (own depot) | - | Read (own depot) |
| Record crew leave | Full | Full | - | Full (own depot) |
| Create/edit stops | Full | Read | Full | Read |
| Create/edit route proposals | Full | Read | Full | Read |
| Run overlap / coverage analysis | Full | Full | Full | - |
| Approve/reject route proposals | Full | Full | - | - |
| Timetables and trip generation | Full | Read | Full | Read |
| Start scheduling runs | Full | Full | - | Full (own depot) |
| View schedules, duties, conflicts | Full | Full | Read | Full (own depot) |
| Manual assignment override | Full | Full | - | Full (own depot) |
| Override SOFT rule with reason | Full | Full | - | - |
| Validate schedule | Full | Full | - | Full (own depot) |
| Publish schedule | Full | Full | - | - |
| Reports and dashboard | Full | Full | Route reports | Full (own depot) |
| Audit log | Full | Full (own depot) | - | - |

A depot-bound manager is restricted to their own depot. An HQ manager, who has no depot binding, sees all depots.

Hard statutory constraints cannot be overridden by any role, including ADMIN.

## 8.4 Other security controls

Mapped against the OWASP API Security Top 10:

```text
API1 Broken object-level authorization
  -> depot scope in every specification, 404 on out-of-scope,
     integration tests per role

API2 Broken authentication
  -> short-lived RS256 JWT, refresh rotation, lockout,
     rate limiting

API3 Broken object-property-level authorization
  -> separate request DTOs per operation, so status, depotId
     and version cannot be mass-assigned; response DTOs mask
     PII by role

API4 Unrestricted resource consumption
  -> max page size 100, request body size limit, geometry
     vertex cap, run queue limits per user, rate limiting

API5 Broken function-level authorization
  -> deny by default, @PreAuthorize on services, matrix tests

API6 Sensitive business flows
  -> publish and approve restricted to MANAGER, idempotency
     keys on run creation

API8 Security misconfiguration
  -> actuator limited to health and info publicly, the rest
     on an internal network; strict CORS allow-list;
     security headers

API9 Improper inventory management
  -> OpenAPI generated from code, only /api/v1 exposed
```

Data protection: TLS in transit, a least-privilege database role with no runtime DDL except through the Flyway role, no secrets in the repository, PII masking in logs, and an append-only audit table with UPDATE and DELETE revoked.

---

# 9. API Design

## 9.1 Conventions

```text
Base path      /api/v1
Format         JSON; geometry as GeoJSON Feature / FeatureCollection
Naming         plural nouns, kebab-case paths, camelCase JSON
Long ops       202 Accepted + Location header, then poll or SSE
Idempotency    Idempotency-Key header on run creation and publish
Concurrency    ETag from version; If-Match required on PATCH/PUT
Errors         RFC 7807 ProblemDetail
Times          ISO-8601 with offset for instants;
               HH:mm:ss (may exceed 24) for service-day times
```

A version mismatch on `If-Match` returns 412 or 409.

The error body carries `type`, `title`, `status`, `detail`, `instance`, `traceId` and an `errors[]` array for field-level problems.

## 9.2 Pagination, filtering and sorting

Offset pagination is the default:

```text
GET /api/v1/buses?depotId=12&status=ACTIVE&fuelType=ELECTRIC
                 &q=DL1P&page=0&size=20&sort=fleetNo,asc
```

```json
{
  "content": [
    {
      "id": 101,
      "registrationNo": "DL1PD1234",
      "fleetNo": "E-0421",
      "status": "ACTIVE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 612,
  "totalPages": 31,
  "sort": ["fleetNo,asc", "id,asc"]
}
```

Rules:

- Default size is 20 and maximum size is 100. Larger values are clamped rather than rejected.
- A negative page returns 400. A page beyond the end returns empty content, not an error.
- Each resource has a sort whitelist. An unknown sort field returns 400.
- `id` is always appended as a tiebreaker, so ordering is stable.
- Filters are typed query parameters bound to a filter record such as `BusFilter` or `DutyFilter`, then converted into JPA specifications.
- Range filters use `from` and `to` pairs, validated so that `from <= to`.
- Enum values are validated, and the error lists the allowed values.
- Spatial filters are `bbox=minLon,minLat,maxLon,maxLat` and `near=lon,lat&radiusM=500`.
- `includeTotal=false` skips the count query on very large tables and returns a slice with `hasNext` instead.

Keyset pagination is used for high-volume, append-heavy collections: trips, block events, audit logs and duty assignments.

```text
GET /api/v1/audit-logs?entityType=DUTY_ASSIGNMENT&limit=100
                      &cursor=eyJhdCI6IjIwMjYtMDktMTZUMTA6...
```

The cursor is an opaque Base64-encoded sort key and id pair. The query is:

```sql
WHERE (at, id) < (:at, :id)
ORDER BY at DESC, id DESC
LIMIT :limit
```

## 9.3 Endpoint catalogue

All paths are relative to `/api/v1`. Endpoints marked `[P]` are paginated and filterable.

Authentication:

```text
POST   /auth/login                       public
POST   /auth/refresh                     public (refresh token)
POST   /auth/logout                      authenticated
```

Users and depots:

```text
GET    /users                       [P]  ADMIN
POST   /users                            ADMIN
PATCH  /users/{id}                       ADMIN
GET    /depots                      [P]  all
POST   /depots                           ADMIN
```

Buses and crew:

```text
GET    /buses                       [P]  all (depot-scoped)
POST   /buses                            ADMIN, MANAGER
PATCH  /buses/{id}/status                ADMIN, MANAGER, SCHEDULER
GET    /crew                        [P]  ADMIN, MANAGER, SCHEDULER
POST   /crew                             ADMIN, MANAGER
POST   /crew/{id}/leaves                 ADMIN, MANAGER, SCHEDULER
GET    /crew/{id}/duties            [P]  ADMIN, MANAGER, SCHEDULER
```

Bus filters are depot, status, type, fuel, AC and a free-text query. Crew filters are role, depot, status, `licenceExpiringBefore`, `availableOn` and qualification.

Stops and routes:

```text
GET    /stops                       [P]  all
POST   /stops                            ADMIN, PLANNER
GET    /routes                      [P]  all
GET    /routes/{id}                      all
POST   /routes                           ADMIN, PLANNER
PUT    /routes/{id}/patterns/{direction} ADMIN, PLANNER
POST   /routes/overlap-analysis          ADMIN, MANAGER, PLANNER
GET    /routes/{id}/overlaps        [P]  ADMIN, MANAGER, PLANNER
POST   /routes/{id}/submit               ADMIN, PLANNER
POST   /routes/{id}/decision             ADMIN, MANAGER
GET    /coverage/zones              [P]  ADMIN, MANAGER, PLANNER
```

Stop filters are bbox, near, terminal, relief point and a free-text query. Route filters are route number, depot, status, bbox and `passesNear`.

`POST /routes/overlap-analysis` runs an ad-hoc analysis on a GeoJSON geometry that has not been saved yet, which is what a planner needs while drawing a route.

Timetables and rules:

```text
POST   /timetables                       ADMIN, PLANNER
POST   /timetables/{id}/generate-trips   ADMIN, PLANNER
GET    /trips                       [P]  ADMIN, PLANNER, SCHEDULER, MANAGER
GET    /rule-sets                   [P]  all
PUT    /rule-sets/{id}                   ADMIN
```

Updating a rule set creates a new version rather than editing in place.

Scheduling:

```text
POST   /schedule-runs                    ADMIN, MANAGER, SCHEDULER
GET    /schedule-runs/{id}               ADMIN, MANAGER, SCHEDULER
GET    /schedule-runs/{id}/events        ADMIN, MANAGER, SCHEDULER
GET    /schedules                   [P]  ADMIN, MANAGER, SCHEDULER
GET    /schedules/{id}/blocks       [P]  ADMIN, MANAGER, SCHEDULER
GET    /schedules/{id}/duties       [P]  ADMIN, MANAGER, SCHEDULER
GET    /schedules/{id}/handovers    [P]  ADMIN, MANAGER, SCHEDULER
PATCH  /duty-assignments/{id}            ADMIN, MANAGER, SCHEDULER
POST   /schedules/{id}/validate          ADMIN, MANAGER, SCHEDULER
GET    /schedules/{id}/conflicts    [P]  ADMIN, MANAGER, SCHEDULER
POST   /schedules/{id}/publish           ADMIN, MANAGER
```

`/schedule-runs/{id}/events` is a Server-Sent Events stream carrying run progress.

`PATCH /duty-assignments/{id}` is the manual override and requires `If-Match`.

Reporting and audit:

```text
GET    /reports/fleet-utilization   [P]  ADMIN, MANAGER, SCHEDULER
GET    /reports/crew-hours          [P]  ADMIN, MANAGER, SCHEDULER
GET    /reports/schedule-kpis       [P]  ADMIN, MANAGER
GET    /reports/route-overlaps      [P]  ADMIN, MANAGER, PLANNER
GET    /dashboard/today                  ADMIN, MANAGER, SCHEDULER
GET    /audit-logs                  [P]  ADMIN, MANAGER
```

In total there are 48 endpoints, 22 of them paginated and filterable.

---

# 10. Cross-Cutting Concerns

## Validation

Bean Validation runs on DTOs. Domain invariants live inside aggregates. Geometry is validated in `GeoJsonCodec` and again by database check constraints.

## Error handling

A `@RestControllerAdvice` maps exceptions to `ProblemDetail`:

```text
validation failure            -> 400
not found or out of scope     -> 404
optimistic lock failure       -> 409
precondition failed           -> 412
business rule violation       -> 422
rate limit exceeded           -> 429
```

## Transactions

Transactions are declared at the service layer. `spring.jpa.open-in-view` is false. Queries run in read-only transactions. Snapshot loads use `REPEATABLE READ` so that a long-running engine load sees one consistent picture.

## Optimistic locking

`@Version` is present on `Bus`, `CrewMember`, `Route`, `RoutePattern`, `Duty`, `DutyAssignment` and `RuleSet`.

## Performance

JDBC batching with `batch_size=500` and ordered inserts, DTO projections and entity graphs to avoid N+1 queries, HikariCP tuning, a Caffeine cache for rule sets and reference data, and materialized views for reports.

## Concurrency

Virtual threads handle requests (`spring.threads.virtual.enabled=true`). A bounded platform-thread pool runs the CPU-bound engine work, because virtual threads bring no benefit there.

## Auditing

Domain events are handled by a `@TransactionalEventListener(BEFORE_COMMIT)` that writes the audit row in the same transaction, so an audit entry can never exist without its change or vice versa. Spring Data `@CreatedBy` and `@LastModifiedBy` fill actor fields.

## Observability

Micrometer metrics include:

```text
scheduling.run.duration
scheduling.conflicts{type}
route.overlap.query.duration
```

A correlation-ID filter puts the trace id in MDC, logs are JSON, and Actuator exposes liveness and readiness probes.

## Configuration

Profiles are `local`, `test` and `prod`. Secrets come from environment variables or a secret manager. Configuration binds to type-safe `@ConfigurationProperties`.

## Migrations

Flyway, with files named `V{n}__description.sql`, run by a separate migration database role at startup or as a pipeline step.

## Documentation

springdoc-openapi serves `/v3/api-docs`, with Swagger UI enabled outside production.

## Architecture tests

ArchUnit rules check module boundaries and engine purity on every build.

---

# 11. Deployment

```text
+---------------------------------------------------+
|        Container host / Kubernetes namespace      |
|                                                   |
|   Nginx / Ingress  (TLS, rate limit)              |
|          |                                        |
|     +----+----+                                   |
|     |         |                                   |
|  app pod 1  app pod 2                             |
|     |         |                                   |
|     +----+----+                                   |
|          |                                        |
|          v                                        |
|   PostgreSQL + PostGIS (primary)                  |
|          |                                        |
|          v                                        |
|   WAL archive / backups (PITR)                    |
|                                                   |
|  app pods --> Prometheus --> Grafana              |
+---------------------------------------------------+
```

The image is a layered Spring Boot jar on a JRE 21 base image, run as a non-root user.

Local development uses Docker Compose with `postgis/postgis:16-3.4`.

Liveness and readiness come from Actuator. Readiness also checks database connectivity and Flyway state.

Scaling means adding app replicas. Run workers coordinate through `SKIP LOCKED`, so no extra configuration is needed.

Backups are a daily base backup plus WAL archiving, and restores are tested quarterly. A backup that has never been restored is not a backup.

---

# 12. Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language | Java 21 | Records, sealed types, virtual threads |
| Framework | Spring Boot 3.x | Web, DI, configuration, Actuator |
| Security | Spring Security 6, OAuth2 Resource Server, Bucket4j | Authentication, RBAC, rate limiting |
| Persistence | Spring Data JPA, Hibernate 6, Hibernate Spatial, JTS | ORM with geometry types |
| Database | PostgreSQL 16, PostGIS 3.4, btree_gist | Relational, spatial, exclusion constraints |
| Migrations | Flyway | Versioned schema |
| Mapping | MapStruct | DTO and entity mapping |
| API docs | springdoc-openapi | OpenAPI 3 |
| Caching | Caffeine | Rule sets, reference data, token versions |
| Observability | Micrometer, Prometheus, Grafana | Metrics and alerts |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, jqwik, ArchUnit, Spring Security Test | Quality gates |
| Load testing | k6 or Gatling | Latency and throughput |
| Build and run | Maven, Docker, Docker Compose | Build and environments |

---

# 13. Key Architecture Decisions

Each decision below records what was chosen, what was rejected, and why.

## ADR-01 — Modular monolith

Microservices were the alternative. Consistency requirements, team size and simpler operations decided it.

## ADR-02 — Geometry analytics in PostGIS

The alternative was JTS in application memory. PostGIS wins because of GiST indexes and set-based SQL, and because the whole network never has to be loaded into the JVM.

## ADR-03 — Store 4326 plus a generated 32643 column

The alternatives were the geography type alone, or transforming on every query. The chosen approach gives accurate metric buffers and lengths on indexable columns.

## ADR-04 — Framework-free engine, greedy construction plus local search

The alternatives were a MILP or column-generation solver now, or a library such as OptaPlanner or Timefold. The chosen approach is deterministic, explainable and fast enough for depot-sized problems. The stage interfaces allow a solver to be added later without rewriting the pipeline.

## ADR-05 — Database exclusion constraints on published assignments

The alternative was application-level checks only. Exclusion constraints guarantee no double booking even under races and application bugs.

## ADR-06 — Self-issued RS256 JWT with refresh rotation

The alternatives were server sessions or an external identity provider. JWTs allow stateless horizontal scaling. An external provider such as Keycloak can replace the issuer later without changing resource-server code.

## ADR-07 — Service-day seconds for schedule times

The alternative was `LocalTime` or timestamps everywhere. Service-day seconds handle post-midnight service naturally and match the GTFS convention.

## ADR-08 — Database-backed run queue with SKIP LOCKED

The alternatives were Kafka or RabbitMQ, or in-memory `@Async`. The database queue is reliable, multi-instance safe, and needs no extra infrastructure.

## ADR-09 — Offset pagination by default, keyset for high-volume tables

The alternative was offset everywhere. Keyset avoids deep-offset scans and unstable pages on large, frequently changing tables.

## ADR-10 — Immutable published schedules with versioning

The alternative was in-place edits. Immutability gives auditability and a safe rollback path.
