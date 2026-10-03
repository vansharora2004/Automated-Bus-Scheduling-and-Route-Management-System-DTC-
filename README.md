# Automated Bus Scheduling & Route Management System

A vehicle and crew scheduling backend for a city bus network, built around the Delhi Transport Corporation as
the reference case. It takes a published timetable and produces a legal, costed operating plan: which bus runs
which trips, which person drives which bus, and what is wrong with the answer.

---

## 1. Project Overview

A bus operator's day is three problems stacked on top of each other.

**The timetable** says a route runs every twenty minutes from 06:00 to 20:00. That is a promise to passengers,
not a plan.

**Vehicle scheduling** turns that promise into buses. A bus arriving at a terminus at 09:40 can take the 10:05
departure from the other terminus only if it can physically get there, after the minimum layover, with enough
battery left to finish the day. Chain the trips wrongly and you need twenty per cent more buses than you own.

**Crew scheduling** turns bus work into human work. A bus block can run seventeen hours; a person cannot. The
block has to be cut into legal duties — at most eight hours of work, at most five of them unbroken, inside a
twelve-hour spread — and the cuts can only fall where a relief driver can physically reach the bus. Then named
people have to be assigned, and they have leave, weekly rest days, licence expiry dates and a legal rest period
since yesterday's duty.

Done by hand this is weeks of work per depot and the result is quietly illegal in places nobody notices. This
system does it in under a second per depot-day, explains every trip it could not cover and every duty it could
not staff, and refuses to publish a plan that breaks a hard rule.

The scope is deliberate: it is the planning and scheduling back end. There is no real-time vehicle tracking, no
passenger-facing app and no payroll integration.

---

## 2. Key Features

- **JWT authentication** with RS256, pinned algorithm, and refresh-token rotation with replay detection
- **RBAC** across four roles — ADMIN, MANAGER, PLANNER, SCHEDULER — enforced at both the URL and the service
- **Depot-level access control**, so a depot-bound user cannot see or touch another depot's data
- **Master data** for depots, stops, buses and crew, with optimistic locking and CSV import that defaults to a
  dry run
- **Routes and GIS** in PostGIS: geometry validation, overlap detection between route patterns, and service
  coverage analysis against a population grid
- **Timetables and trip generation** from headway bands, with running times resolved by time of day
- **Deadhead calculation** with measured values where they exist and flagged estimates where they do not
- **Vehicle scheduling** — greedy best-fit block building, plus an optimal matching builder used as the
  fleet-size lower bound
- **Linked and unlinked duties** — crew staying with one bus, or combining pieces of work from several
- **Relief opportunities and handovers**, with transfer time and buffer enforced
- **Crew assignment** by minimum-remaining-values, with eligibility reason codes and fairness scoring
- **Conflict detection** against an independent validation pass, not the code that produced the schedule
- **Schedule publishing** — immutable, versioned, superseding, and guarded by database exclusion constraints
- **Reports** over published schedules only, with streamed CSV export
- **Live dashboard** for the current service day, cached for thirty seconds
- **Audit logging** — append-only, partitioned, keyset-paginated, with PII redacted by role
- **Metrics and monitoring** — Micrometer, Prometheus scrape config and seven alert rules
- **Docker deployment** — multi-stage build, layered jar, non-root container, health probes

---

## 3. Complete Scheduling Pipeline

```text
Authentication          log in, receive an RS256 access token and a refresh token
        ↓
Master Data             depots, stops, buses, crew — the physical facts
        ↓
Routes / GIS            draw patterns, validate geometry, detect overlap, measure coverage
        ↓
Timetables              one per route per day type, with a validity window
        ↓
Headway bands           "every 20 minutes from 06:00 to 10:00"
        ↓
Trips                   generated departures, with running time by time of day
        ↓
Vehicle Blocks          trips chained onto buses, with layover, deadhead, depot returns, charging
        ↓
Bus Assignment          physical buses on blocks, respecting workshop windows and electric range
        ↓
Duties                  blocks cut into legal crew duties at relief points
        ↓
Handovers               recorded wherever a bus changes crew
        ↓
Crew Assignment         named people, filtered by eligibility and ranked by fairness
        ↓
Validation              independent re-check; zero hard conflicts or it does not pass
        ↓
Publish                 atomic, versioned, supersedes the previous version
        ↓
Reports / Dashboard     KPIs over published schedules; live view of today
        ↓
Audit log               every change, who made it and why
```

**What each stage does**

| Stage | Output | Key rule |
|---|---|---|
| Timetables | A validity window per route and day type | No two active timetables may overlap for one route and day type |
| Trips | Departures with arrival times | Count equals the sum over bands of `ceil(window / headway)` |
| Blocks | One bus's working day | `block.end + layover + deadhead ≤ trip.start` |
| Bus assignment | Bus per block | Workshop windows and electric range are respected |
| Duties | One person's working day | ≤ 8 h work, ≤ 5 h unbroken, ≤ 12 h spread |
| Crew assignment | Named person per duty | Depot, role, leave, weekly off, licence, rest, weekly hours |
| Validation | Conflict list | Hard conflicts block publication |
| Publish | An immutable version | One published schedule per depot-day, enforced by the database |

Every trip is either in exactly one block or on the uncovered list with a reason. Every duty is either staffed or
carries a histogram explaining why not — *"18 considered: 9 INSUFFICIENT_REST, 6 ON_LEAVE, 3 LICENCE_INVALID"*.

---

## 4. Architecture

A modular monolith. Modules talk through service interfaces and domain events, never through each other's
repositories, and an ArchUnit test enforces it.

### Request path

```text
Controller          HTTP, validation, response shaping. No business rules.
    ↓
Service             business rules, transactions, authorization
    ↓
Repository          Spring Data JPA, or JdbcTemplate for bulk and spatial work
    ↓
PostgreSQL + PostGIS
```

### Scheduling engine

The engine is **plain Java with no framework at all** — no Spring, no JPA, no Hibernate, no Jackson, no clock.
An architecture test fails the build if any of those are imported into it. That purity is what makes the
algorithms deterministic and unit-testable in milliseconds instead of needing a database and a context.

```text
ScheduleSnapshotLoader      one consistent read: trips, buses, stops, travel times
        ↓
Vehicle Scheduling          GreedyBestFitBlockBuilder  (default)
                            MinFleetMatchingBlockBuilder (lower bound)
        ↓
ReliefOpportunityFinder     where a crew change is physically possible
        ↓
Duty Construction           LinkedDutyBuilder    (crew stays on one bus)
                            PieceCutter + UnlinkedDutyBuilder + LocalSearchImprover
        ↓
Crew Assignment             EligibilityFilter → FairnessScorer → MrvCrewAssigner
        ↓
Validation                  independent re-derivation of every invariant
        ↓
SchedulePersister           all of it, in one transaction
```

The snapshot matters: the engine runs for seconds and asks millions of questions, so it reads everything once.
Querying as it went would let a bus enter maintenance halfway through and produce a plan that was never valid at
any single moment.

### Infrastructure actually present

| Present | Used for |
|---|---|
| PostgreSQL 16 + PostGIS 3.4 | Everything, including the spatial work and the run queue |
| Spring Boot 3.5 | The application framework |
| Spring Security + OAuth2 Resource Server | JWT authentication and RBAC |
| JPA / Hibernate 6 + Hibernate Spatial | Entity mapping, including geometry |
| Flyway | Eleven ordered migrations |
| Server-Sent Events (Spring MVC `SseEmitter`) | Live run progress |
| Micrometer + Prometheus registry | Metrics |
| Docker / Docker Compose | Local database and production packaging |
| Testcontainers | Integration tests against a real PostGIS |

**Not used, and deliberately not:** there is no Redis, no RabbitMQ and no WebSocket. The run queue is a database
table claimed with `SELECT ... FOR UPDATE SKIP LOCKED`, because the work is already transactional with the data
it reads and writes — a broker would add a second system that can disagree with the database about whether a job
ran. Caching is a thirty-second in-memory window on the dashboard, which does not justify a cache server.
Progress streaming is SSE, which is one-directional and therefore does not need WebSocket.

---

## 5. Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Records, pattern matching, sealed-style modelling in the engine |
| Spring Boot 3.5.3 | Application framework |
| Spring Web MVC | REST API and SSE streaming |
| Spring Security 6 | Filter chain, method security, RBAC |
| Spring OAuth2 Resource Server | JWT validation with a pinned RS256 algorithm |
| Spring Data JPA / Hibernate 6 | Entity mapping and repositories |
| Hibernate Spatial + JTS | Geometry types in entities |
| PostgreSQL 16 | Relational store, range types, exclusion constraints, partitioning |
| PostGIS 3.4 | Spatial indexes, projection, overlap and coverage analysis |
| Flyway | Versioned schema migrations |
| JdbcTemplate | Bulk inserts and spatial queries where JPA would be the wrong tool |
| Caffeine | Token-version cache and login rate-limit buckets |
| Bucket4j | Per-address login rate limiting |
| Apache Commons CSV | RFC 4180 import and export |
| Micrometer + Prometheus | Metrics and scrape endpoint |
| Springdoc OpenAPI | Generated API documentation and Swagger UI |
| Testcontainers | Real PostGIS in integration tests |
| JUnit 5 + AssertJ | Tests |
| ArchUnit | Enforces engine purity and module boundaries |
| Spotless (Palantir) | Formatting, checked in `verify` |
| SpotBugs | Static analysis, on demand |
| OWASP Dependency-Check | Vulnerability scan, in a `security-scan` profile |
| Docker / Compose | Local database and production image |

---

## 6. Project Structure

```text
src/main/java/com/dtc/transit/
├── common/                  cross-cutting: errors, paging, filtering, time, audit, config, web
│   ├── audit/               AuditEvent and the listener that writes it
│   ├── config/              Clock, async executor, metrics, OpenAPI, time properties
│   ├── error/               ApiExceptionHandler and the typed exceptions
│   ├── paging/              PageResponse, CursorResponse, SortWhitelist
│   └── time/                ServiceTime — service-day seconds
├── security/                JWT, filter chain, depot scoping, rate limiting
├── user/                    accounts and roles
├── masterdata/              depot, stop, bus, crew
├── route/                   route, pattern, overlap, coverage  (PostGIS lives here)
├── timetable/               timetable, headway, trip, deadhead, calendar
├── scheduling/
│   ├── engine/              ← plain Java, no framework
│   │   ├── model/           immutable input and output records
│   │   ├── vehicle/         block builders
│   │   ├── relief/          relief opportunity finder
│   │   ├── duty/            piece cutter, linked and unlinked duty builders, metrics
│   │   ├── assignment/      bus assigner, eligibility, fairness, MRV crew assigner
│   │   ├── constraint/      Constraint<T> catalogue and evaluators
│   │   └── search/          local search improver
│   ├── rules/               rule sets as versioned JSONB data
│   ├── schedule/            schedule, blocks, snapshot loader, persister, revalidation
│   ├── duty/                duties, pieces, handovers as stored
│   ├── crew/                crew assignment, override, snapshot loader
│   ├── run/                 run queue, worker, reaper, SSE broadcaster
│   └── api/                 scheduling controllers
├── reporting/               KPI reports, CSV export, dashboard
├── audit/                   audit trail query
└── seed/                    reproducible S / M / L datasets

src/main/resources/db/migration/   V1 … V11
src/test/java/com/dtc/transit/     mirrors the above, plus support/ fixtures
ops/monitoring/                    Prometheus scrape config and alert rules
ops/load/                          k6 load test
docs/                              architecture, implementation plan, runbook, deployment
```

---

## 7. Database

PostgreSQL 16 with PostGIS 3.4. The schema is built by **eleven Flyway migrations**, applied in order:

| Migration | Contents |
|---|---|
| `V1` | Extensions: `postgis`, `btree_gist`; sequences |
| `V2` | Security: `app_user`, `user_role`, `refresh_token` |
| `V3` | `audit_log`, range-partitioned by month |
| `V4` | Master data: depot, stop, bus, bus unavailability, crew and its history |
| `V5` | Routes and coverage: service area, route, pattern, pattern stops, running times, overlap, grid |
| `V6` | Timetables and trips: timetable, headway band, trip, deadhead, calendar exception |
| `V7` | Scheduling: rule set, schedule run, schedule, vehicle block, block event, bus assignment, conflict |
| `V8` | Duties: piece of work, duty, duty piece, handover |
| `V9` | Crew: `duty_assignment` **including its exclusion constraint** |
| `V10` | Reporting: three materialized views with their unique indexes |
| `V11` | Least-privilege roles, and making the audit log genuinely append-only |

`ddl-auto` is `validate`. Hibernate never creates or alters anything; a mapping that disagrees with the schema
fails at startup rather than silently diverging.

### Spatial data

Two coordinate systems, on purpose. Geometry is stored in **EPSG:4326** because that is the interchange format,
and a **generated EPSG:32643 column** holds the projected copy that every metric operation uses:

```sql
geom_utm geometry(LineString, 32643)
         GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED
```

Distance in degrees is not a distance. Transforming at query time would also make the GiST index unusable, so
the projected copy is maintained by the database and indexed.

### Constraints that carry the real guarantees

Application code can have bugs and requests can race, so the invariants that matter are in the database:

```sql
-- A crew member can never hold two overlapping published duties, across dates and depots.
EXCLUDE USING gist (crew_member_id WITH =, work_period WITH &&)
WHERE (schedule_status = 'PUBLISHED' AND status <> 'CANCELLED')

-- A bus can never be in two overlapping published blocks.
EXCLUDE USING gist (bus_id WITH =, period WITH &&) WHERE (schedule_status = 'PUBLISHED')

-- One queued or running scheduling run per depot and date.
CREATE UNIQUE INDEX one_active_run_per_depot_day ON schedule_run (depot_id, service_date)
  WHERE status IN ('QUEUED', 'RUNNING');

-- One published schedule per depot and date.
CREATE UNIQUE INDEX one_published_schedule_per_depot_day ON schedule (depot_id, service_date)
  WHERE status = 'PUBLISHED';

-- Overlapping timetable validity, and overlapping headway bands, both refused.
EXCLUDE USING gist (route_id WITH =, day_type WITH =, validity WITH &&) WHERE (status = 'ACTIVE')
```

They apply to **published** rows only, deliberately: a replacement version is prepared while the live one is
still live, so its drafts legitimately overlap.

### Partitioning and volume

`audit_log` and `block_event` are range-partitioned by month and service date respectively — they are the two
fastest-growing tables, and retention becomes a partition drop rather than a mass delete.

### Materialized views

`mv_fleet_utilization_daily`, `mv_crew_hours_weekly` and `mv_schedule_kpis`. Each filters on
`status = 'PUBLISHED'`, which is the single most important line in the reporting migration: a republished day
leaves a superseded version carrying the same blocks and duties, and counting both would double every figure.
Each has a unique index, because `REFRESH MATERIALIZED VIEW CONCURRENTLY` requires one.

### Audit log

Append-only, and enforced rather than asserted. `UPDATE`, `DELETE` and `TRUNCATE` are revoked from the
application role and blocked by a trigger on **every partition**, because a statement against a partition does
not fire the parent's trigger. An audit trail the application can edit is not evidence of anything.

### Least-privilege roles

| Role | Rights | Used by |
|---|---|---|
| `app_rw` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`. No DDL. No `UPDATE`/`DELETE` on `audit_log` | The application |
| `migrator` | DDL | Flyway |

Created `NOLOGIN` with no password — a password in a repository is not a password. Granting login is a deployment
step, documented in the runbook.

---

## 8. Scheduling Algorithms

### Greedy best-fit block building — the default

Trips are sorted by start time, then by id. For each trip, every open block is tested for feasibility:

```text
block.end + minLayover(block.lastTrip) + deadhead(block.endStop → trip.startStop) ≤ trip.start
```

Among feasible blocks the cheapest continuation wins, ranked by **dead running first, then idle time**. That
order matters more than it looks: ranking by idle time alone actively *prefers* chains that need dead running,
because the empty movement eats the gap and makes the slack look smaller. Correcting that took dead running from
46% to 19% on the reference dataset.

A long idle gap becomes a depot return — pull-in, park, charge for an electric bus, pull-out — rather than a bus
standing at a terminal for four hours. Complexity is O(T × B).

### Minimum-fleet matching — the lower bound

The fewest buses that can cover a set of trips is a minimum path cover of a DAG, which equals the trip count
minus a maximum bipartite matching. Solved with an augmenting-path search over a time-windowed graph, and
reported next to the greedy answer so the cost of greediness is visible rather than assumed acceptable. On the
reference dataset: 88 blocks against an optimum of 82.

It is the bound, not the default — it ignores electric range, depot parking and which physical bus goes where.

### Piece cutter — dynamic programming

Cuts a block into pieces of work at relief opportunities, minimising piece count and preferring cuts at the
depot, subject to a length range. O(R²) per block. Greedy cutting works most of the time and then leaves an
unusable two-minute tail, because an early greedy choice cannot see what it leaves behind.

### Linked duty construction

The crew stays on one bus. From a cursor, **every** later relief point is evaluated and the legal candidate
closest to the target working day wins, with a penalty for leaving a tail too short to be worth paying for.

Checking every candidate is not laziness about optimisation — hard constraints are **not monotone in segment
length**. A longer segment can be legal where a shorter one was not, because the break that satisfies the
continuous-work rule may lie beyond the shorter segment's end. Stopping at the first failure produces wrong
answers that look entirely reasonable.

### Unlinked duty construction

A crew may change buses at relief points, so short pieces from different buses combine into full duties. Greedy
construction: take the earliest unassigned piece, then repeatedly add the cheapest piece that is both reachable
(gap ≥ transfer time + handover buffer) and legal. The candidate cost prefers a small gap, the same relief point,
and a gap that can serve as the break the duty still owes.

### Local search improvement

Two moves — move a piece between duties, merge two short duties — applied only when every hard constraint still
holds **and** the cost strictly decreases. Seeded `SplittableRandom` and a wall-clock budget, so the same input
and seed produce the same output. Correctness before optimisation: a legal greedy schedule is usable, and a
cheaper one that breaks a rest rule is not, with the violation invisible in the output.

Three further moves from the design — swap, split, re-cut — are **not implemented**.

### MRV crew assignment

Minimum remaining values: the duty with the fewest eligible people is filled first, candidate counts recomputed
as bookings are made. Filling in time order instead spends the flexible people early and leaves the one duty only
three drivers can legally take with none of them free — a failure that looks like a staffing shortage and is
really an ordering mistake.

Eligibility applies eleven hard rules, cheapest first, and every rejection records a code. Fairness then ranks
the eligible by weekly load, duties already taken in this run, and — for night duties only — night work over the
last 28 days. Counting a night worker's history against them for a *day* turn would push them toward more night
work, which is backwards.

### Constraint catalogue

Every rule implements one interface and is registered in a catalogue used twice: as a fast feasibility check
during construction, and as a full independent re-check afterwards. Every limit is read from the rule set, never
a constant — which is what makes changing the maximum continuous work a configuration change rather than a
release.

---

## 9. Security

**Authentication.** Self-issued JWT, **RS256**, with the algorithm pinned. An unsigned (`alg=none`) token and an
HS256 token signed with the public key are both refused — both are classic confusion attacks, and both are
tested.

**Tokens.** Access tokens last fifteen minutes; refresh tokens last seven days, are stored only as hashes, and
rotate on use. A reused refresh token is treated as theft: every token for that account is revoked immediately.

**Revocation without a blacklist.** Each account carries a `token_version` claim. Bumping it invalidates every
token for that user within the cache TTL (sixty seconds), with no blacklist to maintain and no shared state.

**RBAC.** Four roles — ADMIN, MANAGER, PLANNER, SCHEDULER — enforced at the URL level in the filter chain *and*
on service methods. Not belt-and-braces for its own sake: URL rules must come first for multipart endpoints,
because parts are parsed before method security would run, and an unauthorised caller would otherwise get a
parsing error instead of a refusal.

A test asserts that **every mapped endpoint has at least one authorization case**, so coverage cannot quietly
rot as endpoints are added.

**Depot scoping.** A depot-bound user's queries are rewritten to their own depot, and a cross-depot read returns
404 rather than 403 — a 403 confirms the resource exists to someone who should not know.

**Rate limiting and lockout.** Ten login attempts per minute per address (Bucket4j), and an account locks after
five failures for fifteen minutes. The failure counter is written in its own transaction, because the login
failure rolls back the one it was in — a bug that made lockout silently impossible until it was found.

**Audit logging.** Every write records actor, action, entity, before, after, reason and trace id. The actor is
resolved from the security context when the row is written, so no caller can claim to be someone else. The table
is append-only at the database level.

**PII masking.** Audit payloads are redacted for anyone who is not an administrator — a before/after pair is a
whole entity state, and a crew record carries a name, employee code and licence number. Crew names are reduced
to initials for schedulers, who roster by employee code. Licence numbers are never put in a response at all,
which is the stronger control.

**Database least privilege.** The application connects as `app_rw`: DML only, no DDL, and no `UPDATE`/`DELETE`
on the audit log.

**Other controls.** HSTS with subdomains, frame options deny, content-type options nosniff, CORS with no wildcard
default, stateless sessions, bcrypt password hashing with a timing equaliser on unknown usernames, and the
actuator restricted to ADMIN apart from the health probes.

---

## 10. API Documentation

Springdoc generates the OpenAPI document from the controllers.

| | Path |
|---|---|
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |

Both require an **ADMIN** token — the actuator and the API docs are not public. Obtain a token first:

```sh
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<bootstrap password>"}'
```

Then use **Authorize** in the Swagger UI and paste the access token. The bearer scheme is declared in the
document, so try-it-out works.

Swagger UI is **disabled in the `prod` profile**; the JSON document remains available for tooling.

API groups: authentication, users, depots, buses, crew, stops, routes, coverage, timetables, trips, deadheads,
calendar, rule sets, schedule runs, schedules, duties, handovers, duty assignments, reports, dashboard, audit
logs.

---

## 11. Running Locally

### Prerequisites

- **JDK 21** or later (the build targets 21 with `--release`)
- **Docker Desktop**, for the database and for Testcontainers
- No Maven install needed — use the bundled wrapper

### 1. Start PostgreSQL + PostGIS

```sh
docker compose up -d
```

This runs `postgis/postgis:16-3.4` on **host port 55432**, not 5432. A locally installed PostgreSQL commonly
already owns 5432; override with `DB_PORT` if 55432 is also taken.

### 2. Run the application

```sh
./mvnw spring-boot:run
```

Flyway applies all eleven migrations at startup. To create the first administrator, supply a bootstrap password —
without one, no admin is created:

```sh
APP_SECURITY_BOOTSTRAP_PASSWORD='choose-a-strong-one' ./mvnw spring-boot:run
```

The application listens on **port 8080**. Check it is up:

```sh
curl -s http://localhost:8080/actuator/health
```

### 3. Migrations

Migrations run automatically on startup. There is no separate command, and `ddl-auto: validate` means Hibernate
will refuse to start if the mapping and the schema disagree.

### 4. Load a reference dataset

Three reproducible datasets regenerate byte-for-byte from a fixed seed:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed \
  -Dspring-boot.run.arguments="--size=S --purge"
```

| Size | Depots | Routes | Trips | Buses | Crew |
|---|---|---|---|---|---|
| S | 1 | 20 | 1,200 | 120 | 300 |
| M | 10 | 200 | 12,000 | 1,200 | 3,000 |
| L | 45 | 855 | 51,300 | 5,130 | 12,825 |

### 5. Run the tests

```sh
./mvnw clean verify
```

Testcontainers starts its own PostGIS container, so **Docker must be running**. The suite takes roughly six
minutes. `verify` also runs the Spotless format check.

Narrower runs:

```sh
./mvnw test -Dtest=BlockBuildingTest          # engine unit tests, no database
./mvnw test -Dtest='*SchedulingPerformanceTest'
./mvnw -Psecurity-scan verify                 # dependency vulnerability scan (needs NVD_API_KEY)
./mvnw spotless:apply                         # fix formatting
```

---

## 12. Docker Deployment

### Build the image

```sh
docker build -t dtc-scheduling:0.1.0 .
```

Multi-stage: the build stage carries the JDK, Maven and the dependency cache — about 700 MB with no business on a
production host — and the runtime stage carries a JRE and the application, with nothing that could compile or
download code.

- **Non-root**: runs as uid 10001. A container escape starting as root is a host compromise.
- **Layered jar**: a code-only redeploy ships a few hundred kilobytes, not the whole fat jar.
- **Container-aware heap**: `MaxRAMPercentage=75` rather than a fixed `-Xmx`, which either wastes the memory
  limit or exceeds it and gets the container killed.
- **`exec` entrypoint**, so the JVM is PID 1 and receives SIGTERM directly.

Tests are not run in the image build: Testcontainers needs a Docker daemon, and starting containers inside an
image build is a problem nobody should have to debug.

### Production compose

```sh
docker compose -f docker-compose.prod.yml up -d
```

Runs the database with WAL archiving, the application, Prometheus and Grafana, with CPU and memory limits and
health probes on both the database and the application.

### Environment variables

Every secret comes from the environment, declared with `${VAR:?message}` so a missing one fails at startup rather
than silently running with a development value.

| Variable | Required | Notes |
|---|---|---|
| `DB_USER`, `DB_PASSWORD` | yes | Database superuser, for container initialisation |
| `APP_DB_USER`, `APP_DB_PASSWORD` | yes | The `app_rw` role: DML only |
| `MIGRATOR_DB_USER`, `MIGRATOR_DB_PASSWORD` | yes | The `migrator` role: DDL, used by Flyway |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | yes | PEM. Unset means an ephemeral pair per restart |
| `CORS_ALLOWED_ORIGINS` | yes | No wildcard default; the API is credentialed |
| `BOOTSTRAP_PASSWORD` | first deploy | Creates the initial admin; remove afterwards |
| `GRAFANA_PASSWORD` | yes | |

### Health checks

| Probe | Path |
|---|---|
| Liveness | `/actuator/health/liveness` |
| Readiness | `/actuator/health/readiness` |

`start_period` is ninety seconds, because Flyway runs eleven migrations before readiness turns green. Health
detail is hidden in production — which database is down is information an attacker uses.

Full procedure in [docs/Deployment.md](docs/Deployment.md).

---

## 13. Testing

**Final verification: 592 tests, 0 failures, 0 errors — BUILD SUCCESS.** Spotless clean across 337 files.

Integration tests run against a **real PostGIS container**, not an in-memory database. The schema depends on
PostGIS types, GiST indexes, `tstzrange` exclusion constraints, partial unique indexes and declarative
partitioning, none of which H2 reproduces — testing against anything else would verify the wrong database.

| Area | What is covered |
|---|---|
| Engine unit tests | Block building, matching lower bound, relief opportunities, bus assignment, duty metrics against hand-computed fixtures, constraint boundaries (exactly at the limit passes, one second over fails) |
| Authorization | 171 cases across every role and endpoint, plus a test that every mapped endpoint has a case |
| Security | Token forgery (`alg=none`, HS256 confusion), revocation, rate limiting, lockout |
| Spatial | Overlap detection with known-answer geometries, coverage, lon/lat swap rejection |
| Scheduling | Run lifecycle to publish, `SKIP LOCKED` contention with eight threads, reaper recovery, determinism across runs |
| Crew | End-to-end assignment, and the exclusion constraint tested with **raw SQL that bypasses the application entirely** |
| Reporting | Figures matched against independent hand-written queries, superseded versions excluded, PII masking |
| Query plans | `EXPLAIN ANALYZE` on the hot paths, asserting indexes are used |
| Architecture | ArchUnit: engine purity and module boundaries |

Performance, measured in this environment:

```text
S dataset, one depot-day   1,200 trips → 88 blocks (optimum 82)   249 ms   target 5 s
M dataset, one depot-day   1,200 trips → 88 blocks                146 ms
Dead running                                                      19.3%
```

---

## 14. Monitoring

Micrometer exposes metrics at `/actuator/prometheus` (ADMIN only). Beyond the JVM, pool and HTTP metrics that
come free:

| Metric | Why |
|---|---|
| `scheduling.run.duration{mode}` | Linked and unlinked have different cost profiles; one timer would be bimodal |
| `scheduling.run.failures{reason}` | The `reason` tag is bounded: `no_fleet`, `rule_set`, `worker_lost`, `other` |
| `scheduling.conflicts{type}` | The most useful operational signal in the system |
| `scheduling.runs.queued` / `.running` | Backlog and in-flight work |
| `route.overlap.duration` | The heaviest spatial query |
| `api.page.size` | What clients actually ask for, before clamping |

Tag cardinality is kept low deliberately — a tag per depot would be 45 series per metric and a tag per run id
would be unbounded, which is how a monitoring system is brought down by the thing it monitors.

**Alerts** in [`ops/monitoring/alerts.yml`](ops/monitoring/alerts.yml): service down, API p95 latency, run
failures, stuck runs, queue backlog, connection pool saturation, revalidation storm. Each links to a runbook
section. There are deliberately few — an alert that fires without requiring action trains people to ignore the
ones that do.

Prometheus scrape config: [`ops/monitoring/prometheus.yml`](ops/monitoring/prometheus.yml). The actuator is
ADMIN-only, so the scrape carries a bearer token.

---

## 15. Backup & Recovery

Base backup plus WAL archiving, so recovery can target a point in time rather than only the last backup. Full
procedure, including the **SQL invariant checks to run before letting the application back in**, is in
[docs/Deployment.md § 4–5](docs/Deployment.md).

Operational procedures — stuck runs, blocked publish, revalidation storms, pool saturation, key rotation — are in
[docs/Runbook.md](docs/Runbook.md).

---

## 16. Documentation

| Document | Contents |
|---|---|
| [README.md](README.md) | This file |
| [docs/Architecture.md](docs/Architecture.md) | Module decomposition, data architecture, engine design, security architecture, ADRs |
| [docs/Implementation.md](docs/Implementation.md) | The eleven-phase delivery plan with per-phase exit criteria |
| [docs/Project statement.md](docs/Project%20statement.md) | Problem statement, scope and objectives |
| [docs/Edge case.md](docs/Edge%20case.md) | Catalogued edge cases and how each is handled |
| [docs/Evaluation.md](docs/Evaluation.md) | Test strategy and evaluation criteria |
| [docs/Runbook.md](docs/Runbook.md) | On-call procedures |
| [docs/Deployment.md](docs/Deployment.md) | Packaging, deployment, backup, restore, monitoring, security status |

---

## 17. Project Status

**Phases 1–11 are implemented.** Final verification: **592 tests, 0 failures, 0 errors, BUILD SUCCESS**, Spotless
clean.

| Phase | Delivered |
|---|---|
| 1 | Spring Boot + PostGIS foundation, Testcontainers, ArchUnit |
| 2 | Authentication, RS256 JWT, RBAC, audit log |
| 3 | Master data with a shared paging and filtering framework |
| 4 | Routes, PostGIS geometry validation, overlap detection, coverage |
| 5 | Timetables, trip generation, deadheads, reproducible S/M/L datasets |
| 6 | Vehicle scheduling, run queue with `SKIP LOCKED`, heartbeat and reaper |
| 7 | Rule sets as data, constraint catalogue, linked duties |
| 8 | Piece cutting, unlinked duties, handovers, local search |
| 9 | Crew assignment, conflicts, override with `If-Match`, publish, revalidation |
| 10 | Reports, materialized views, CSV export, dashboard, audit query, PII masking |
| 11 | Metrics, alerts, least-privilege roles, Docker image, runbook |

### Remaining non-blocking items

These are known and documented rather than hidden:

- **ZAP API scan and the dependency vulnerability scan were configured and documented, not executed.** ZAP needs
  a deployed instance and an authenticated session; the dependency scan needs an `NVD_API_KEY` and a multi-minute
  database download. Neither was available in this environment.
- **The L-dataset full-fleet benchmark with parallel depot runs was not executed.** S and M depot-days are
  measured and well inside target.
- **Three local-search moves** from the design — swap pieces, split duty, re-cut — are not implemented. The two
  that are implemented are verified never to break a hard constraint.
- **Conductor slots are modelled but not created.** No trip in the current data demands one; the assignment loop
  is written so adding them is a data change.
- **Standby crew pool** is not implemented.
- **`duty_assignment` is not partitioned**, though the design lists it. PostgreSQL cannot place an exclusion
  constraint on a partitioned table, and the double-booking guarantee is worth more than the partitioning.
- **The S dataset cannot staff about 26 of its duties** once weekly rest, leave, licence expiry and the ten-hour
  rest rule apply. That is correct behaviour reported with reason histograms; the generator allocates crew per
  route without modelling availability.
- **Grafana dashboard JSON** is referenced by the compose file but not authored. The alert rules are complete.
- **`mapstruct` and `jqwik` are declared in `pom.xml` but unused** and could be removed.
- **`updated_at` is only set on insert** — no trigger maintains it. Affects every entity extending
  `BaseEntity`, and has done since Phase 1.
- **SSE progress is per-instance** (in-memory). With several instances a client may see only the completion
  event; the run row carries `progress` as the fallback.
