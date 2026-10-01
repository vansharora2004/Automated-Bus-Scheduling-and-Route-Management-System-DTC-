# Automated Bus Scheduling and Route Management System — Implementation Plan

> **Status: design document.** Nothing described here is implemented yet. The repository currently contains documentation only. This is the roadmap the project will follow, starting with Phase 1 once the documentation is finalised.

## Overview

The system will be built in 11 phases.

Each phase will end with a working, tested, demonstrable increment and explicit verification checks. Nothing moves to the next phase until the current phase's checks pass.

Durations are indicative, for one to two developers.

```text
Phase 1  -> Spring Boot + PostGIS setup                1 week
Phase 2  -> Auth + JWT + RBAC                          1 week
Phase 3  -> Master data + pagination/filtering         2 weeks
Phase 4  -> Routes + Spatial + overlap                 2 weeks
Phase 5  -> Timetables + trips + datasets              1 week
Phase 6  -> Vehicle scheduling                         1 week
Phase 7  -> Rest rules + linked duties                 2 weeks
Phase 8  -> Unlinked duties + handovers                2 weeks
Phase 9  -> Crew assignment + conflicts + publish      2 weeks
Phase 10 -> Reports + dashboard + audit                1 week
Phase 11 -> Final testing + Docker + monitoring        1 week

Total                                                  ~16 weeks
```

Phase order is not arbitrary. Each phase depends on what came before:

```text
P1 Setup
      |
      v
P2 Security
      |
      v
P3 Master data
      |
      +----------------+
      |                |
      v                v
P4 Routes & GIS    P5 Timetables
      |                |
      +--------+-------+
               |
               v
      P6 Vehicle scheduling
               |
               v
      P7 Rest rules & linked duties
               |
        +------+------+
        |             |
        v             v
P8 Unlinked      P9 Assignment
  duties            & publish
        |             |
        +------+------+
               |
               v
        P10 Reporting
               |
               v
        P11 Hardening
```

Phases 1 to 5 build infrastructure, and no scheduling happens in them. Phases 6 to 9 are the actual product. Phase 10 reads what Phase 9 publishes, and Phase 11 proves the whole thing is fast, secure and deployable.

## The repeatable loop for every phase

The same order will be used inside each phase, because it prevents most rework:

```text
Flyway migration -> Entity -> Repository -> Service -> Controller -> Tests
```

The migration is written first so the schema stays the source of truth. Hibernate will run with `ddl-auto: validate`, so it can never silently change a table.

## Definition of done

These apply to every phase, not only the ones where they are obviously relevant:

1. Code follows the module and layer boundaries defined in the architecture document. ArchUnit tests pass.
2. Unit tests cover domain logic. Integration tests using Testcontainers cover repositories and native SQL.
3. New endpoints appear in OpenAPI with request and response examples and the roles they require.
4. Authorization tests exist for every new endpoint and role combination.
5. A Flyway migration is added, and the schema starts cleanly from an empty database.
6. The relevant edge cases from the edge-case document have tests.
7. The phase's "before moving on" checks are met and demonstrated.

Algorithm designs, data model and API contracts are specified in the architecture document. This document covers the order of work, not the algorithms themselves.

---

# Phase 1 — Spring Boot + PostGIS Setup

## Objective

Phase 1 will create an empty but runnable Spring Boot project connected to a local PostGIS database, with migrations and test infrastructure in place. No business features are built in this phase.

The goal is that every later phase has somewhere to put its code and a way to test it against a real database.

## Build order

1. Generate the project — Java 21, Maven, base package `com.dtc.transit`.
2. Write `docker-compose.yml` using `postgis/postgis:16-3.4` and start it.
3. Write `application.yml` — datasource, `open-in-view: false`, `ddl-auto: validate`, UTC JDBC timezone, batch size 500.
4. Write the first Flyway migration creating the `postgis` and `btree_gist` extensions plus the ID sequences.
5. Create the empty package folders for all modules now, so later phases have a home.
6. Build the `common` module basics — problem-details error handler, injected `Clock` bean, correlation ID filter, service-time utilities.
7. Add the Testcontainers base class and one throwaway test that connects to it.
8. Add the ArchUnit rule that `scheduling.engine` must not import Spring or JPA.
9. Add springdoc-openapi and Actuator, exposing health, info and prometheus.
10. Add formatting and static analysis.

## Key components

```text
TransitApplication          application entry point
common/                     error handling, paging, filtering, geo, time
docker-compose.yml          local PostGIS
V1__extensions.sql          postgis, btree_gist, sequences
PostgisContainerTest        Testcontainers base class
ArchUnit rules              module boundaries, engine purity
```

## Project structure

The full package layout will be created in this phase, even though most folders stay empty until later:

```text
dtc-bus-scheduling/
  pom.xml
  docker-compose.yml
  src/main/java/com/dtc/transit/
    TransitApplication.java
    common/       config, error, paging, filtering, geo, time, audit base
    security/     security config, token service, auth, depot access
    user/
    masterdata/   depot/ bus/ crew/ stop/
    route/        route/ pattern/ overlap/ coverage/ proposal/
    timetable/    timetable/ headway/ trip/ deadhead/ calendar/
    scheduling/
      engine/     model/ vehicle/ relief/ duty/ assignment/
                  constraint/ search/     <- no Spring here
      rules/      rule set storage and binding
      run/        run queue, worker, reaper, SSE
      schedule/   schedule, block, duty, assignment, conflict
      api/
    reporting/
    audit/
  src/main/resources/
    application.yml
    db/migration/
  src/test/java/com/dtc/transit/
    architecture/  ArchUnit rules
    support/       Testcontainers base, test data builders, test tokens
```

The test tree will mirror the main tree.

## Libraries to add

Core:

```text
spring-boot-starter-web
spring-boot-starter-validation
spring-boot-starter-data-jpa
spring-boot-starter-security
spring-boot-starter-oauth2-resource-server
spring-boot-starter-actuator
```

Geospatial and persistence:

```text
hibernate-spatial          (version from the Spring Boot Hibernate BOM)
jts-io-common              (GeoJSON read/write, aligned with jts-core)
postgresql driver
flyway-core
flyway-database-postgresql
```

Support:

```text
mapstruct
springdoc-openapi-starter-webmvc-ui
caffeine
micrometer-registry-prometheus
```

Test:

```text
spring-boot-starter-test
spring-security-test
spring-boot-testcontainers
testcontainers-postgresql
testcontainers-junit-jupiter
jqwik
archunit-junit5
```

Every version will be pinned to the latest stable release compatible with the chosen Spring Boot version.

## Before moving on

```text
./mvnw verify passes from a clean checkout, including one
Testcontainers test.

The app starts against docker compose up, and Flyway applies
the first migration.

/actuator/health returns UP.

The ArchUnit rule "engine has no Spring or JPA imports" exists
and passes.
```

---

# Phase 2 — Auth + JWT + RBAC

## Objective

Phase 2 will add login, JWT tokens, the four roles and depot scoping, so that every endpoint built in later phases is protected from the moment it exists.

Security comes before any domain data, because retrofitting depot scope onto finished endpoints is far harder than building with it.

## Build order

1. Migrations for `app_user`, `user_role`, `refresh_token` and `audit_log`, with the audit log partitioned by month.
2. Token service — RS256 keys, 15-minute access tokens, 7-day refresh tokens stored hashed, with rotation and reuse detection.
3. Auth endpoints — login, refresh, logout.
4. Security configuration — stateless, deny by default, JWT roles converter, CORS allow-list, security headers.
5. Token-version filter, rejecting tokens whose version claim is behind the user's current version, with a 60-second cache.
6. Depot access evaluator and the depot-scope helper that every later query will reuse.
7. Login rate limiting and account lockout.
8. Audit event infrastructure, writing the audit row in the same transaction as the change.
9. Seed an admin user from an environment-provided bootstrap password, local profile only.

## Key components

```text
TokenService            issue, validate and rotate tokens
AuthController          login, refresh, logout
SecurityConfig          filter chain, deny by default
TokenVersionFilter      revocation within the cache TTL
DepotAccessEvaluator    single-entity depot check
DepotScope              query-level depot restriction
AuditListener           writes audit rows before commit
```

Roles:

```text
ADMIN
MANAGER
PLANNER
SCHEDULER
```

## Do this early

The permission matrix test will be written in this phase, even with only a handful of endpoints existing. Every later phase adds rows to it, and it fails loudly when someone adds an endpoint without classifying it.

Building the matrix test at the end instead would mean reconstructing the intended rules from finished code, which is exactly when mistakes get baked in.

## Before moving on

```text
No token on a non-public endpoint      401
Wrong role                             403
Another depot's data                   404

JWTs are signed with RS256.
alg=none and HS256-confusion tokens are rejected, and this is
tested.
A disabled user's token is rejected after the cache TTL.
Login and user administration are audited.
```

---

# Phase 3 — Master Data + Pagination/Filtering

## Objective

Phase 3 will add CRUD for depots, buses, crew and stops, together with one reusable pagination, filtering and sorting framework.

The framework is the real deliverable. Around 22 endpoints will eventually use it, so writing it once means they all behave identically.

## Build order

1. Migrations for `depot`, `bus`, `bus_unavailability`, `crew_member`, `crew_depot_history`, `crew_leave`, `crew_qualification` and `stop`, including GiST indexes.
2. Build the paging and filtering framework **before any controller**.
3. Entities with optimistic-locking versions, sequence IDs using a pooled allocator, and geometry point columns.
4. Depot and stop endpoints first, since they are simplest, then bus and crew.
5. Registration-number normalisation, with employee codes kept as text so leading zeros survive.
6. CSV bulk import with a per-row validation report and a dry-run mode.
7. Depot scope applied to every list and detail query.

## Key components

```text
PageResponse      uniform list response shape
SortWhitelist     rejects unknown sort fields, appends id tiebreaker
CursorPage        keyset pagination for high-volume tables
Filter records    typed query parameters per resource
Specifications    filter records converted to JPA predicates
GeoJsonCodec      geometry in and out
CSV importer      dry run, per-row errors, all-or-nothing per file
```

## Why the framework comes first

If the framework is written after two controllers exist, those two controllers get rewritten. Writing it first also forces the paging rules — max size 100, stable sort, whitelist — to be decided once rather than per endpoint.

## Before moving on

```text
size above 100        clamped to 100
page = -1             400
page beyond the end   200 with empty content
unknown sort field    400 listing allowed fields
cross-depot access    404

EXPLAIN ANALYZE shows index usage for filtered list queries on
100k synthetic rows.

The paging and filtering framework is reused by at least four
list endpoints.
```

---

# Phase 4 — Routes + Spatial + Overlap

## Objective

Phase 4 will store routes as real map geometry, detect overlap between a proposed route and existing routes, and measure service coverage by zone.

This is the phase where PostGIS and Hibernate Spatial do actual work rather than just being configured.

## Build order

1. Migrations for `route`, `route_pattern` with its generated projected column and GiST index, `pattern_stop`, `running_time_band`, `route_overlap`, `coverage_zone`, `grid_cell`, `service_area` and the cell-coverage materialized view.
2. The geometry codec and validation pipeline.
3. The route pattern entity with its geometry mapped through Hibernate Spatial.
4. The overlap native query, returning an interface projection.
5. Enrichment in Java — shared stops, direction agreement, severity.
6. Coverage service — grid-cell view refresh, zone coverage query, proposal coverage gain.
7. Proposal state machine with guarded transitions.
8. Spatial filters on the route and stop list endpoints.
9. Asynchronous overlap recomputation when an active pattern changes.

## Key components

```text
RoutePattern            geometry entity (LineString)
GeoJsonCodec            parse and serialise GeoJSON
OverlapAnalysisService  native spatial query plus Java enrichment
CoverageService         grid-cell coverage and gaps
Proposal state machine  propose, review, approve, activate
```

The validation pipeline will run in this order:

```text
parse GeoJSON
     |
     v
check SRID and CRS
     |
     v
check lon/lat order against the service area
     |
     v
at least 2 distinct points
     |
     v
ST_IsValid
     |
     v
optional ST_SimplifyPreserveTopology
     |
     v
vertex cap (e.g. 5,000)
```

## Build test fixtures before the query

Hand-drawn geometries with known answers will be created before the overlap SQL is written — a 1 km straight line, two identical routes, a parallel road 40 m away, a perpendicular crossing.

Spatial bugs are invisible without known-answer fixtures. A query can return plausible-looking numbers that are completely wrong.

## Before moving on

```text
Identical routes                       ratio ~ 1.00
Parallel road 40 m away, 25 m buffer   ratio 0.00
Perpendicular crossing                 ratio 0.00
3 km shared on a 10 km route           ratio 0.30 +/- 0.01

Swapped lon/lat input is rejected with a helpful message.
Overlap analysis p95 stays under 500 ms.
The proposal workflow is enforced, audited and
authorization-tested.
```

---

# Phase 5 — Timetables + Trips + Datasets

## Objective

Phase 5 will turn headways into actual trip times, maintain the deadhead travel-time matrix, and generate the reproducible S, M and L test datasets.

This phase gates everything after it. Without datasets there is no way to test or measure Phases 6 to 11.

## Build order

1. Migrations for `timetable`, `headway_band`, `trip`, `deadhead` and `calendar_exception`.
2. Trip generator — walk the headway bands per direction, looking up running time by departure band.
3. Band validation — no overlapping bands, end after start, plausible average speed, no duplicate trips.
4. Deadhead matrix, with an estimate fallback flagged as estimated rather than silently used as measured.
5. Day-type resolution with calendar exceptions for holidays and special events.
6. Timetable changes mark dependent drafts and published schedules as needing revalidation.
7. Synthetic data generator under a seed profile, as a CLI runner.
8. Optional GTFS static importer, subject to the data source's licence terms.
9. Timetable and trip endpoints, with the trip list using keyset pagination.

## Key components

```text
TripGenerator     headway bands -> trips
DeadheadMatrix    terminal-to-terminal travel times by band
Calendar          day-type resolution with overrides
Data generator    S, M and L datasets from a fixed seed
```

Dataset targets:

```text
S   1 depot,   20 routes,  ~120 buses,  ~1,200 trips,   ~300 crew
M   10 depots,           ~1,200 buses, ~12,000 trips, ~3,000 crew
L   45 depots,          5,000+ buses,  ~50,000 trips, ~12,000 crew
```

All three will carry realistic structure: morning and evening peaks, radial and ring routes, terminals as relief points, an electric-bus share, a leave rate and a licence-expiry distribution. They will also contain deliberately infeasible pockets, so conflict detection is exercised rather than assumed.

## Before moving on

```text
Generated trip counts match the analytical count, the sum over
bands of ceil(band length / headway).

S, M and L regenerate deterministically from the same seed,
with a stable checksum.

A holiday override changes the timetable picked for that date,
and this is tested.
```

---

# Phase 6 — Vehicle Scheduling

## Objective

Phase 6 will chain a depot-day's trips onto buses to form blocks, assign physical buses to those blocks, and run the whole thing as an asynchronous background job.

This is where the scheduling engine begins, and it will be written as plain Java with no Spring or JPA dependencies.

## Build order

1. Engine model as plain Java records — trip view, depot context, vehicle class, block, block event, vehicle schedule.
2. The greedy best-fit block builder, handling minimum layover, deadhead, vehicle class, EV range with reserve, mid-day depot returns and charging events.
3. The bus assigner, honouring unavailability windows and preferring an even distribution of kilometres.
4. Migrations for the basic rule set, `schedule_run`, `schedule`, `vehicle_block`, `block_event`, `bus_assignment` with its exclusion constraint, `conflict`, and the partial unique indexes.
5. Snapshot loader (read-only, repeatable read) and the batch persister.
6. The run queue — create with an idempotency key, worker claiming with `SKIP LOCKED`, heartbeat, and the reaper for lost workers.
7. The minimum-fleet matching builder last, since it is the lower bound rather than the default.
8. Run and schedule endpoints.

## Key components

```text
GreedyBestFitBlockBuilder      default block builder
MinFleetMatchingBlockBuilder   optional builder and PVR lower bound
BusAssigner                    blocks -> physical buses
ScheduleSnapshotLoader         one consistent read of all inputs
SchedulePersister              batched writes in one transaction
RunWorker / RunReaper          job queue and crash recovery
```

The algorithms themselves are specified in the architecture document. This phase implements them.

## Watch out

The matching builder is tempting to build first because it is optimal. It is not the default, and building it first delays a working pipeline. Greedy first, matching second.

## Before moving on

```text
The S dataset gets 100% trip coverage, or every uncovered trip
carries a reason, in under 5 seconds.

Every trip appears in exactly one block or in the uncovered list.
Consecutive trips within a block satisfy layover plus deadhead.
EV blocks never exceed usable range.

A second run for the same depot and date, while one is queued or
running, returns 409.

Killing a worker mid-run leads the reaper to mark it failed, with
no partial schedule rows left behind.
```

---

# Phase 7 — Rest Rules + Linked Duties

## Objective

Phase 7 will add the labour rules as configurable data, then cut each bus block into legal crew duties in which the crew stays with one bus.

A bus block can run 17 hours, but a person cannot. This phase is what turns bus work into human work.

## Build order

1. The typed rule-set record with validation, stored as JSONB and resolved by effective date and depot, plus its endpoints.
2. The constraint interface, then one constraint at a time, each with its own unit test before the next is added.
3. The incremental evaluation helper for fast feasibility checks during construction.
4. The relief opportunity finder.
5. The linked duty builder, including split linked duties around mid-day depot parking.
6. Duty metrics — sign-on and sign-off, platform, paid, breaks, spread-over, overtime — and duty type classification.
7. Handover records at cut points.
8. Migrations for `piece_of_work`, `duty`, `duty_piece` and `handover`.
9. Duty, handover and validation endpoints.

## Key components

```text
RuleSet                  labour rules as versioned data
Constraint catalogue     max work, continuous work, break,
                         spread-over, minimum paid duty
ReliefOpportunityFinder  where a bus can change crew
LinkedDutyBuilder        block -> duties on the same bus
```

Rules covered in this phase are the ones **inside** a duty. Rest between consecutive duties and weekly rest are enforced in Phase 9, during crew assignment.

## Watch out

Hard constraints are **not monotone** in segment length. A longer segment can be legal when a shorter one was not, because a qualifying break appears later in the block.

Every candidate cut point must therefore be checked. Stopping at the first failure will produce wrong results that look reasonable.

## Before moving on

```text
A 17-hour block splits into 2-3 duties, each legal, with
handovers at relief points.

A block containing a 6-hour stretch with no relief point produces
a NO_FEASIBLE_RELIEF conflict rather than being silently accepted.

Changing the maximum continuous work value in the rule set changes
the output with no code change.

Constraint boundaries behave correctly: exactly at the limit
passes, one second over fails.

Duty metrics match hand-computed fixtures.
```

---

# Phase 8 — Unlinked Duties + Handovers

## Objective

Phase 8 will let a crew move between buses at relief points, so that short pieces of work from different buses combine into full-length duties.

This is the phase that produces the efficiency gain over linked scheduling, and it is also the phase most likely to produce subtly illegal schedules if rushed.

## Build order

1. The piece cutter, using dynamic programming per block over relief opportunities.
2. Relief-point transfer times, plus depot sign-on and sign-off travel.
3. The unlinked duty builder — greedy construction only at first.
4. The handover feasibility constraint, plus maximum pieces and maximum bus changeovers as soft constraints.
5. The local search improver, adding one move at a time and verifying each before adding the next.
6. Determinism — stable ordering, the seed persisted on the run, output hash in the run metrics.
7. Run progress events streamed over SSE.

## Key components

```text
PieceCutter            block -> pieces of work
UnlinkedDutyBuilder    pieces across buses -> duties
Handover constraint    gap >= transfer time + buffer
LocalSearchImprover    MovePiece, SwapPieces, MergeDuties,
                       SplitDuty, ReCut
```

## Get correctness before optimisation

The greedy construction will be made legal and complete before any local search is added.

A legal greedy schedule is usable. A faster, cheaper schedule that violates a rest rule is not, and the violation may not be obvious in the output.

## Before moving on

```text
Every piece belongs to exactly one duty, and the union of pieces
equals the union of block work.

Every duty passes all hard constraints.
Every handover gap is at least transfer time plus buffer.

Local search never increases cost and never introduces a hard
violation.

Same input and same seed produce an identical output hash across
10 runs.

The M dataset in unlinked mode completes per depot in under 60 s,
including a 30 s search budget.
```

---

# Phase 9 — Crew Assignment + Conflicts + Publish

## Objective

Phase 9 will assign duties to named crew members legally and fairly, explain whatever cannot be staffed, allow safe manual overrides, and publish the final schedule atomically.

This is the phase where double-booking protection becomes real, enforced by the database rather than only by application code.

## Build order

1. Migration for `duty_assignment` **including the exclusion constraint** — not added later.
2. Crew history loader — rolling 7 days for limits, 28 days for fairness.
3. Eligibility filter with reason codes, then the fairness scorer, then the MRV assigner.
4. Conflict persistence with the aggregated rejection histogram.
5. Validation service, producing a validated state only when there are zero hard conflicts.
6. Manual override with `If-Match`, re-validating the affected crew window.
7. Publish service — lock the schedule row, supersede the old version, publish the new one, all in one transaction.
8. Revalidation job for master-data changes affecting published schedules.
9. Crew duty history, override, conflict and publish endpoints.

## Key components

```text
EligibilityFilter    hard rules with reason codes
FairnessScorer       hours, night duties, pair continuity
MrvCrewAssigner      hardest slot first
ValidationService    full re-check before publish
PublishService       atomic, idempotent version switch
RevalidationJob      reacts to master-data changes
```

Hard eligibility will cover depot, role, active status, leave, weekly off, licence validity, qualifications, rest since the previous duty, weekly work limit and weekly rest.

## Test the database guard directly

An overlapping published assignment will be inserted with raw SQL, bypassing the application entirely.

If it succeeds, the exclusion constraint is wrong — and that would never be discovered through the API, because the application checks would mask it.

## Before moving on

```text
The L dataset, 5,000+ buses, publishes across all depots with
zero hard violations.

Every unassigned duty carries a reason histogram.

Publishing with open hard conflicts returns 422.
Publishing twice with the same idempotency key returns the same
result and creates one version.

A raw-SQL overlapping published assignment is rejected by the
exclusion constraint.

Two schedulers overriding the same assignment concurrently: one
succeeds, the other gets 412 or 409.

Override and publish flows are fully audited.
```

---

# Phase 10 — Reports + Dashboard + Audit

## Objective

Phase 10 will build the KPI reports, the live operations view for the current day, and a searchable audit trail.

It reads what Phase 9 publishes. Nothing in this phase changes a schedule.

## Build order

1. Materialized views for fleet utilization, crew hours and schedule KPIs, each with the unique index that concurrent refresh requires.
2. Report endpoints with filters, pagination and streamed CSV export.
3. The today dashboard, reading live tables with a short cache.
4. SSE for run progress, and optionally a dashboard push on publish.
5. The audit log endpoint with keyset pagination and filters.
6. PII masking by role in crew responses.

## Key components

```text
mv_fleet_utilization_daily   PVR, in-service ratio, dead-km ratio
mv_crew_hours_weekly         hours, overtime, night duties
mv_schedule_kpis             duties, platform-to-paid, splits
/dashboard/today             live operations snapshot
/audit-logs                  keyset-paginated audit trail
```

## Watch out

Reports must aggregate published schedules only. Including superseded versions would double count every republished day.

Grouping must be by service date, not calendar date, or duties after midnight land in the wrong day.

## Before moving on

```text
Report figures match an independent hand-written query on the
S dataset.

Only published schedules are included; superseded versions are
excluded.

Every write endpoint produces exactly one audit record.

A depot with no blocks returns null ratios rather than an error.
```

---

# Phase 11 — Final Testing + Docker + Monitoring

## Objective

Phase 11 will load-test the system, run security scans, add metrics and dashboards, and package the application for deployment.

Nothing new is built here. This phase proves that what was built is fast enough, secure enough and deployable.

## Build order — performance

1. Load tests using the documented request mix.
2. Review query plans for the top 20 queries, then add or adjust indexes.
3. Check for N+1 queries using Hibernate statistics.
4. Size the connection pool and the bounded engine executor.
5. Run the full-fleet scheduling benchmark on the L dataset with parallel depot runs.

## Build order — security

1. Dependency vulnerability scan in the build.
2. API scan driven by the OpenAPI spec, authenticated.
3. Verify security headers, CORS and actuator exposure. Scan the tree for secrets.
4. Set up least-privilege database roles.

```text
app_rw     DML only
migrator   DDL, used by Flyway
           UPDATE and DELETE revoked on audit_log
```

## Build order — monitoring

1. Add custom metrics.
2. Build a dashboard with alerts for run failures, p95 latency, pool saturation and stuck runs.
3. Emit JSON logs carrying trace id, user id and depot id.

```text
scheduling.run.duration{mode}
scheduling.run.failures
scheduling.conflicts{type}
route.overlap.duration
api.page.size
```

## Build order — packaging and operations

1. Multi-stage Dockerfile using a layered jar, a non-root user and a JRE 21 base image.
2. Production compose files or Kubernetes manifests with liveness and readiness probes and resource limits.
3. Backup and restore procedure, base backup plus WAL, with a restore rehearsal.
4. Runbook covering a stuck run, a blocked publish, a revalidation storm, key rotation and restore.

## Where Docker appears

Docker is used in two phases, for two different reasons:

```text
Phase 1   docker compose runs the local PostGIS database
Phase 11  the application itself is packaged as an image
```

## Before moving on

```text
All performance targets are met, or deviations are documented
with a root cause.

No high or critical findings from the dependency and API scans.

A clean deployment from the image to a fresh environment, with a
restored database backup, succeeds and passes the SQL invariants.
```

---

# Practical Sequencing Advice

Three things will save the most rework, and all three are "do it earlier than feels necessary":

**The security matrix test belongs in Phase 2, not Phase 11.** Built early, it grows with the project. Built late, it has to be reconstructed from finished code, which is exactly when wrong assumptions get locked in.

**The dataset generator belongs in Phase 5.** Phases 6 to 11 all need data to prove anything at all. Without it, every later phase is tested on toy input.

**Geometry fixtures belong in Phase 4, before the overlap SQL.** Spatial bugs produce plausible wrong numbers, and only known-answer fixtures catch them.

If time runs short, Phase 10 can be reduced to the core reports. Phase 9 cannot be skipped — without publish and the double-booking guard, the system produces schedules that nobody can safely use.

---

# Risk Register

## Labour-rule values differ from assumptions

Impact is high: schedules could be illegal, or needlessly over-conservative. Likelihood is high, because the values are assumptions until someone confirms them.

Mitigation: rules are data, not code. Rule sets will be reviewed with DTC HR and legal before go-live.

## Real data quality is poor

Geometry and legacy spreadsheets may both be messy, producing wrong overlaps and failed imports. Likelihood is high.

Mitigation: the Phase 4 validation pipeline, dry-run imports in Phase 3, a data-quality report, and explicit estimated-deadhead flags so nobody mistakes an estimate for a measurement.

## Heuristic quality is insufficient

The system might need more duties or buses than necessary. Likelihood is medium.

Mitigation: lower bounds to measure the gap honestly, local search in Phase 8, and a pluggable interface so a better algorithm can replace it later.

## Full-fleet run time is too long

This would cause missed planning windows. Likelihood is medium.

Mitigation: depot partitioning, parallel workers and a time-bounded search, all measured in Phase 11.

## Scope creep into real-time operations

Live vehicle tracking would delay everything. Likelihood is medium.

Mitigation: explicitly out of scope, and stated as such in the project statement.

## Spatial query performance at scale

Analysis could become slow. Likelihood is low to medium.

Mitigation: generated projected columns, GiST indexes, grid-based coverage and materialized views.

## Concurrency bugs in publish or override

These would cause double booking. Likelihood is low.

Mitigation: database exclusion constraints added in Phase 9 from the start, optimistic locking, and dedicated concurrency tests.
