# Automated Bus Scheduling and Route Management System (DTC)

## 1. Project Overview

This project is a backend system for planning bus operations at Delhi Transport Corporation (DTC).

DTC runs one of the largest city bus operations in India. Every day, someone has to decide which bus runs which trips, which driver and conductor operate which bus, when crews hand over and rest, and whether a newly proposed route simply repeats a route that already exists. Most of this work is still done by hand, using spreadsheets, paper registers and the experience of depot staff.

The system replaces that manual process with a RESTful backend that generates vehicle and crew schedules automatically, checks them against labour and operational rules, and stores routes as real geometry so that overlap and coverage can be measured rather than guessed.

The main capabilities are:

1. Automated scheduling of linked and unlinked duties for a fleet of 5,000+ buses, exposed through REST APIs.
2. Modelling of crew-bus assignments, shift handovers and mandatory rest periods inside the scheduling algorithms.
3. Geospatial route management using PostGIS and Hibernate Spatial, including overlap detection between existing and proposed routes, and service coverage measurement.
4. Role-based access control using Spring Security, with pagination and filtering across 15+ API endpoints, plus reporting for schedulers, planners and managers.

The system is built with Java, Spring Boot, Spring Security, PostgreSQL, PostGIS and Hibernate Spatial.

Numbers marked as assumptions in this document are planning figures used for sizing. They must be checked against real DTC data, standing orders and applicable labour rules before production use.

---

# 2. Background

## 2.1 The organisation

DTC is the state-owned public bus operator for the National Capital Territory of Delhi.

The operation is depot-based. Buses and crew belong to a depot. Each day, buses pull out of the depot, run trips on assigned routes, and pull back in at the end of the day.

The fleet is mixed. It contains CNG buses and a growing share of electric buses, in standard, low-floor, AC and non-AC variants. Electric buses add range and charging constraints that the older fleet did not have.

The scope of this project is 5,000+ buses. Sizing assumptions:

```text
Depots       ~40-50
Crew         ~10,000+
Trips/day    ~50,000
```

## 2.2 How planning works today

The current process runs roughly as follows:

```text
Route planning        -> Planning cell, using maps and field surveys
        |
        v
Timetabling           -> Headways converted to trip times by hand
        |
        v
Vehicle scheduling    -> Excel sheets chaining trips onto buses
        |
        v
Duty scheduling       -> Duty charts built on the bus schedule
        |
        v
Crew rostering        -> Registers and notice boards
        |
        v
Same-day changes      -> Manual edits for breakdowns and absences
```

Stop lists live in spreadsheets. Leave and absence are often handled by phone. Each depot keeps its own copy of everything.

## 2.3 Problems with the current process

Preparing schedules for a depot takes days whenever a timetable changes, so the operation cannot respond quickly to new routes, demand changes or events.

The process is error-prone. Crew or buses get double-booked, rest periods are missed, and licence expiry goes unnoticed until someone checks. The result is cancelled trips, exposure on labour-law compliance, and fatigue risk.

There is no single source of truth. Because each depot keeps its own spreadsheets, data is inconsistent and HQ cannot see the whole network.

Crew utilization is poor. Duties are mostly linked, which means a crew stays with one bus, which produces many short or idle duties and extra overtime.

Fleet utilization is also poor. Chaining trips onto buses by hand needs more buses at peak than necessary, and produces high dead kilometres.

Routes get duplicated. New routes are proposed without any quantitative overlap analysis, so several routes compete on the same corridor while other areas stay unserved.

There is no access control and no audit trail. Anyone with the file can edit it, and nobody knows who changed what.

Reporting is weak. Utilization and crew hours are computed by hand after the fact, so managers decide on stale data.

---

# 3. Problem Statement

DTC needs a centralised, secure backend that turns timetables into conflict-free vehicle and crew schedules automatically, supporting both linked and unlinked duties.

Every schedule must satisfy crew-bus assignment rules, shift-handover feasibility and mandatory rest-period constraints.

The system must also let planners analyse routes geospatially, so that overlap between existing and proposed routes can be detected and service coverage can be measured.

All of this must be exposed through role-scoped, paginated and filterable REST APIs, at the scale of a 5,000+ bus fleet.

---

# 4. Users and Roles

The system has four direct roles.

```text
ADMIN
MANAGER
PLANNER
SCHEDULER
```

## 4.1 Scheduler

Works at depot level. A scheduler can:

- Generate daily and periodic schedules for their own depot.
- Review and fix conflicts.
- Handle absences, breakdowns and last-minute changes.
- Apply manual overrides with a recorded reason.

Today this person spends hours in spreadsheets and finds conflicts only on the day of operation.

## 4.2 Planner

Works in the HQ planning cell. A planner can:

- Create and modify routes, patterns and stops.
- Build timetables and headway bands.
- Run overlap analysis on existing and proposed routes.
- Run coverage analysis by zone.
- Submit route proposals for review.

Today this person has no quantitative tool for duplication or coverage.

## 4.3 Manager

Depot manager or HQ operations. A manager can:

- Approve or reject route proposals.
- Publish validated schedules.
- View utilization, compliance and KPI reports.

## 4.4 Administrator

An administrator can:

- Manage users and roles.
- Manage depots.
- Manage scheduling rule sets.

## 4.5 Indirect users

Crew members are affected by the output but do not use the system directly. They need duties that are fair, legal and predictable.

Commuters are affected through service reliability and a sensible route network.

The transport department and auditors need evidence of compliance and efficiency, which the audit log provides.

---

# 5. Domain Primer

Bus scheduling has its own vocabulary. This section defines the terms used in every other document.

## 5.1 The planning pipeline

```text
Network
(routes, stops, patterns)
        |
        v
Timetable
(trips per route and day type)
        |
        v
Vehicle schedule
(blocks: trips chained onto one bus)
        |
        v
Crew schedule
(duties: work cut at relief points)
        |
        v
Roster
(duties assigned to named crew)
        |
        v
Day of operation
(publish, changes, reports)
```

Overlap and coverage analysis feed back into the network stage.

## 5.2 Glossary

Depot: base where buses are parked and maintained and where crew report. Buses and crew belong to one depot at a time.

Route: a public service identified by a route number such as 534. A route has one or more patterns.

Route pattern: a specific path and stop sequence of a route in one direction, stored as a LineString geometry.

```text
UP
DOWN
LOOP
```

Stop: a boarding point. A terminal is a start or end point where buses lay over.

Relief point: a location, usually a terminal or the depot, where one crew can hand a bus over to another.

Service day: the operating day. It can run past midnight, for example 04:30 to 01:30 the next morning. Times are stored as seconds from the service-day start and may exceed 24:00.

Day type: the calendar category that decides which timetable applies.

```text
WEEKDAY
SATURDAY
SUNDAY
HOLIDAY
```

Headway: the interval between consecutive trips on a route in a time band, for example every 10 minutes in the peak.

Trip: one revenue run of a pattern, from origin to destination, at a scheduled time.

Running time: the scheduled time to complete a trip. It varies by time band.

Layover: scheduled recovery time at a terminal between trips.

Deadhead: non-revenue movement, such as depot to terminal or terminal to terminal.

Pull-out and pull-in: leaving the depot at the start of a block, and returning at the end.

Block: the full sequence of trips, deadheads and layovers operated by one bus in a service day.

Peak vehicle requirement (PVR): the maximum number of buses in service at the same time. It sets fleet size.

Piece of work: a continuous stretch of work on one bus between two relief opportunities.

Duty: one crew member's working day, made of one or more pieces of work plus breaks, sign-on and sign-off.

Linked duty: a duty in which the crew stays with one bus for the whole duty.

Unlinked duty: a duty in which the crew may operate several buses, changing at relief points.

Handover: transfer of a bus from an outgoing crew to an incoming crew at a relief point.

Sign-on and sign-off: paid time for reporting and for closing out.

Platform time: time spent actually operating a bus.

Paid time: the total paid duration of a duty, including platform time, sign-on and sign-off, paid breaks and travel between relief points.

Spread-over: elapsed time from sign-on to sign-off, including unpaid breaks.

Split duty: a duty with a long unpaid gap, typically one piece in the morning peak and one in the evening peak.

Rest period: mandatory rest, covering breaks within a duty, rest between consecutive duties, and weekly rest.

Roster: the assignment of duties to named crew over days, respecting leave, rest and fairness.

Route overlap: the portion of a route's length that runs along an existing route's corridor, within a buffer distance.

Service coverage: the share of an area, or of its population, within walking distance of a served stop.

## 5.3 Linked and unlinked duties

This distinction is the core of the scheduling problem, so it is worth an example.

Bus B1 has a block that runs from 05:30 to 22:30, which is 17 hours. No single crew can legally work that long, so more than one duty must cover it.

With linked scheduling, each crew stays on B1:

```text
Bus B1   |05:30==================13:30|13:30==================22:30|
Crew A   |=== on B1 ===|brk|=== on B1 =|
Crew B                                 |=== on B1 ===|brk|== on B1 =|
                                       ^
                              handover at relief point
                                  (same bus)
```

This is simple to operate. Accountability for the bus, its fuel and its ticketing is clear.

The weakness is that peak-only buses, running for example 07:00-11:00 and 17:00-21:00, produce short inefficient duties or long split duties. Breaks can only happen where that particular bus has a long enough layover.

With unlinked scheduling, crews move between buses at relief points:

```text
Bus B1   |06:00======10:00|
Crew C   |=== on B1 ======|--- break ---|=== on B7 ====|
                          ^
       C hands B1 to Crew D at Terminal X
       C takes over B7 at Terminal X at 10:40

Bus B7                                  |10:40=====14:00|
```

This combines pieces of work from different buses into full-length duties, so there are fewer duties, less paid idle time and less overtime.

The weakness is that it needs feasible handovers in both time and place, a limit on bus changes per duty, and tighter operational control.

The system must support both modes, selectable per depot and per run, and must report which one performs better on the same input.

---

# 6. Objectives

```text
O1 -> Automate linked and unlinked duty scheduling for 5,000+ buses
      through REST APIs, replacing spreadsheet workflows.

O2 -> Model crew-bus assignments, shift handovers and rest-period
      constraints so schedules are conflict-free and resources are
      used better.

O3 -> Provide geospatial route management with overlap detection
      and coverage analysis.

O4 -> Enforce role-based access control and provide paginated,
      filterable APIs with real-time data and reporting.

O5 -> Make every schedule change traceable and auditable.
```

Each objective is measured in the Evaluation document.

O1 is measured by trip coverage, run time at full-fleet scale, and schedule lead time against a baseline.

O2 is measured by hard violations in published schedules, conflict counts before and after, PVR, platform-to-paid ratio and duty count.

O3 is measured by overlap detection precision and recall, query latency and coverage accuracy.

O4 is measured by a fully tested authorization matrix, the endpoint inventory and API latency.

O5 is measured by audit completeness.

---

# 7. Scope

## 7.1 In scope

Master data:

- Depots, with location.
- Buses, including type, fuel, AC flag, status and maintenance windows.
- Crew, including role, licence, qualifications and leave.
- Stops and relief points.

Route management:

- Routes and directional patterns stored as geometries.
- Stop sequences.
- Running-time bands.
- A proposal workflow from propose through review to approval and activation.

Geospatial analysis:

- Route-to-route overlap, including length, ratio, shared stops and direction.
- Coverage gaps by zone.
- The coverage gain of a proposed route.

Timetables:

- Headway bands per day type.
- Trip generation.
- A deadhead travel-time matrix.

Vehicle scheduling:

- Building blocks from trips.
- Depot returns for long idle gaps.
- Electric-bus range limits.

Crew duty scheduling:

- Linked and unlinked modes.
- Relief points and handovers.
- Breaks, spread-over and pieces of work.

Crew assignment:

- Rostering for a service date or date range.
- Eligibility, rest, weekly limits, leave, licences and fairness.

Operations:

- Conflict detection, explanation and manual overrides with optimistic locking.
- Schedule lifecycle and versioning.
- Role-based access control with depot-scoped data access.
- Pagination, filtering and sorting on list endpoints.
- Reporting on fleet utilization, crew hours, schedule KPIs, overlap and today's operations.
- Audit log of every write.
- OpenAPI documentation, Flyway migrations and a Dockerised local environment.

## 7.2 Out of scope

The following are deliberately excluded from the first version. Several are candidates for later work.

- A frontend UI. The system is API-first and any client can consume it.
- Live GPS tracking, ETA prediction and passenger information displays.
- Ticketing, fare collection and revenue accounting.
- Payroll computation. The system exports hours; it does not calculate pay.
- Maintenance management beyond availability windows.
- Demand forecasting and automatic headway optimisation. Planners enter headways.
- An exact mathematical optimiser such as set partitioning or column generation. The first version uses constructive heuristics and local search behind a pluggable interface.
- Multi-operator settlement for cluster bus operators.

---

# 8. Functional Requirements

## 8.1 Master data

```text
FR-MD-01  CRUD for depots, including location as a PostGIS Point.
FR-MD-02  CRUD for buses: registration number (normalised and
          unique), fleet number, depot, bus type, fuel type,
          AC flag, capacity, EV range and status.
FR-MD-03  Bus unavailability windows for maintenance, charging and
          breakdown, stored as time ranges.
FR-MD-04  CRUD for crew: employee code, role, depot with effective
          dates, licence number, class and expiry, qualifications,
          status and weekly off.
FR-MD-05  Crew leave, full day or partial day, and absences.
FR-MD-06  CRUD for stops, with location, terminal flag and
          relief-point flag.
FR-MD-07  Bulk CSV import of legacy spreadsheet data, with a
          per-row validation report.
```

Bus status values:

```text
ACTIVE
UNDER_MAINTENANCE
BREAKDOWN
RETIRED
```

Crew roles:

```text
DRIVER
CONDUCTOR
```

## 8.2 Route management

```text
FR-RT-01  Create and update routes with directional patterns
          supplied as GeoJSON LineString in EPSG:4326.
FR-RT-02  Validate geometry: valid, at least two distinct points,
          inside the service area, correct coordinate order,
          simplified if noisy.
FR-RT-03  Maintain the ordered stop sequence per pattern and flag
          stops that sit far from the line.
FR-RT-04  Overlap detection for an existing or ad-hoc proposed
          pattern, returning overlapping routes with overlap
          length, overlap ratio, shared stops and direction,
          ranked by severity.
FR-RT-05  Coverage analysis: covered area or population share per
          zone within a configurable walking catchment, a list of
          coverage gaps, and the coverage gain of a proposed route.
FR-RT-06  Route proposal workflow, with analysis results attached
          to the proposal.
FR-RT-07  Spatial filters on list endpoints, such as bounding box
          and "passes within X metres of a point".
```

Route lifecycle:

```text
PROPOSED -> UNDER_REVIEW -> APPROVED -> ACTIVE -> RETIRED
                         -> REJECTED
```

## 8.3 Timetables

```text
FR-TT-01  Timetables per route and day type, with validity dates.
FR-TT-02  Headway bands per direction and running-time bands
          per pattern.
FR-TT-03  Trip generation from headway and running-time bands,
          plus manual trip edits.
FR-TT-04  A deadhead travel-time and distance matrix between
          terminals and depots, by time band.
FR-TT-05  Holiday and special-day calendar overrides.
```

## 8.4 Vehicle scheduling

```text
FR-VS-01  Chain a depot's trips for a service date into blocks,
          respecting minimum layover, deadhead feasibility,
          required vehicle class, EV range and bus availability.
FR-VS-02  Insert a depot return when an idle gap exceeds a
          configured threshold.
FR-VS-03  Report trips that cannot be covered, with reasons.
FR-VS-04  Assign physical buses to blocks with no double booking.
```

## 8.5 Duty scheduling

```text
FR-DS-01  Identify relief opportunities in every block, meaning
          relief-point arrivals and depot visits.
FR-DS-02  Linked mode: cut each block into duties that stay on the
          same bus, each satisfying work, continuous-work, break
          and spread-over rules.
FR-DS-03  Unlinked mode: cut blocks into pieces of work and
          combine pieces across buses into duties, enforcing
          handover feasibility and a maximum number of bus
          changes per duty.
FR-DS-04  Classify duties and compute platform, paid, break,
          spread-over and overtime times.
FR-DS-05  Improve the schedule by local search against a
          configurable cost function within a time budget.
          Runs are deterministic for a given seed.
FR-DS-06  Record each handover: bus, relief point, time, outgoing
          duty and incoming duty.
```

Duty classes:

```text
STRAIGHT
SPLIT
BROKEN
```

## 8.6 Crew assignment

```text
FR-CA-01  Assign duties to named crew by role, including a
          conductor where the route or bus requires one.
FR-CA-02  Enforce hard eligibility rules.
FR-CA-03  Apply soft preferences.
FR-CA-04  Explain unassigned duties with rejection reasons
          aggregated over candidates.
FR-CA-05  Maintain standby crew pools for absences.
```

Hard eligibility covers:

- Correct depot.
- Correct role.
- Active status.
- Not on leave.
- Not on weekly off.
- Valid licence on the service date.
- Required qualifications.
- Minimum rest since the previous duty.
- Weekly work limit.
- Weekly rest.

Soft preferences cover:

- Fair distribution of hours.
- Fair distribution of early, late and night duties.
- Continuity of driver and conductor pairs.

## 8.7 Conflicts, overrides and lifecycle

```text
FR-CF-01  Detect and persist conflicts by type and severity,
          each with an explanation and the affected entities.
FR-CF-02  Manual override with optimistic locking and immediate
          re-validation.
FR-CF-03  Validate a schedule. Only schedules with zero hard
          conflicts can be published.
FR-CF-04  Publishing is atomic, supersedes the previous published
          version, and makes the published version immutable.
FR-CF-05  Re-validate published schedules when master data
          changes, and raise new conflicts.
FR-CF-06  Only one active scheduling run per depot and service
          date.
```

Conflict severity:

```text
HARD
SOFT
```

Hard statutory constraints cannot be overridden. Operational soft rules can be overridden by authorised roles, with a mandatory reason.

Schedule lifecycle:

```text
DRAFT -> VALIDATED -> PUBLISHED -> SUPERSEDED
```

## 8.8 Security

```text
FR-SEC-01  Username and password authentication, issuing a
           short-lived JWT access token and a rotating refresh
           token.
FR-SEC-02  Endpoint and method-level authorization against the
           permission matrix.
FR-SEC-03  Depot-scoped access, so depot-bound users see and
           modify only their own depot's data.
FR-SEC-04  Disabling a user or changing their roles takes effect
           within the access-token lifetime.
FR-SEC-05  Login rate limiting and account lockout.
```

## 8.9 Reporting and real-time data

```text
FR-RP-01  Fleet utilization per depot and date: PVR, in-service
          ratio and dead-km ratio.
FR-RP-02  Crew hours per crew, per depot and per week, including
          overtime and the distribution of rest and night duties.
FR-RP-03  Schedule KPIs: duty count, platform-to-paid ratio,
          split-duty share, handovers and conflicts.
FR-RP-04  Route overlap summary and coverage-gap report.
FR-RP-05  A "today" dashboard reflecting the latest committed
          state, with run progress streamed over Server-Sent
          Events.
FR-RP-06  CSV export of reports.
```

The today dashboard shows buses out, unassigned duties, open conflicts and unavailable buses.

## 8.10 Audit

```text
FR-AU-01  Every create, update, delete, publish, override and
          login event is recorded with actor, timestamp, entity,
          before and after values, and reason.
FR-AU-02  The audit log is append-only and queryable with
          pagination and filters.
```

---

# 9. Non-Functional Requirements

## Performance

Paginated list endpoints should stay under 200 ms at p95 with 50 concurrent users.

Overlap analysis should stay under 500 ms at p95.

One depot-day, meaning roughly 150 buses and 1,500 trips (assumption), should schedule in under 60 seconds.

The full fleet of 5,000+ buses should schedule in under 15 minutes with parallel depot runs.

## Scalability

API nodes are stateless and scale horizontally.

Scheduling work is partitioned by depot and service date, which is what makes parallel runs possible.

## Correctness

A published schedule must contain zero hard-constraint violations.

Database constraints act as the final guard against double booking, independently of application code.

## Determinism

The same inputs, the same rule set and the same seed must produce an identical schedule.

## Security

The OWASP API Security Top 10 should be addressed.

Passwords are hashed with BCrypt or Argon2. TLS is used in transit. The application connects with a least-privilege database user.

## Auditability

Every write operation is audited.

## Availability

The target is 99.5% during operating hours, with database backups and point-in-time recovery.

## Maintainability

The system is a modular monolith with clear module boundaries.

The scheduling engine is framework-independent, and engine and constraint code should reach at least 80% line coverage.

## Observability

Structured logs with correlation IDs, Micrometer and Prometheus metrics, and health probes.

## Data integrity

Flyway migrations are versioned. Editable aggregates use optimistic locking.

All timestamps are stored as `timestamptz` in UTC and presented in IST.

## Other

Text is UTF-8 throughout, so Hindi names and stop names work correctly.

The API follows OpenAPI 3, uses a consistent error format based on RFC 7807 Problem Details, and is versioned under `/api/v1`.

---

# 10. Constraints

## 10.1 Regulatory and operational

Working-time rules matter most here. The Motor Transport Workers Act, 1961 is being subsumed into the Occupational Safety, Health and Working Conditions Code, 2020. Its baseline rules include roughly:

```text
Work per day       <= 8 hours
Work per week      <= 48 hours
Rest interval      >= 30 min after no more than 5 hours of work
Spread-over        <= 12 hours per day
Weekly rest        1 day
```

All of these are modelled as configurable rule-set parameters and are never hard-coded. The exact values must be confirmed against the statute and rules currently in force, DTC standing orders and union agreements.

Other operational constraints:

- Crew belong to a depot. Cross-depot loans are exceptional and must be explicit.
- Some bus types require specific qualifications, for example electric buses.
- Electric buses have range limits and need charging windows.
- A published schedule must not change silently. Corrections produce a new version.

## 10.2 Technical

```text
Java 21
Spring Boot 3.x
Spring Security
Spring Data JPA / Hibernate 6
Hibernate Spatial
PostgreSQL 16
PostGIS 3.4
btree_gist extension
```

Geometry is exchanged as GeoJSON in EPSG:4326. Metric computations use a projected CRS for Delhi, UTM zone 43N, EPSG:32643.

---

# 11. Assumptions

1. Scheduling is performed per depot and per service date. Weekly constraints use a rolling 7-day history of assignments.
2. Planners supply timetables, meaning headways and running times. The system does not forecast demand.
3. Relief points are known and flagged on stops and depots.
4. A deadhead travel-time matrix is available. Where it is not, values are estimated from straight-line distance multiplied by a detour factor and divided by average speed, and flagged as estimated.
5. Each bus operates with one driver, plus one conductor where the bus or route requires it. This is configurable.
6. Service days start at a configurable time, defaulting to 03:00 IST. Trips after midnight belong to the previous service day.
7. Historical manual schedules, or a representative sample, can be obtained to form a baseline. If they cannot, a naive rule-based baseline is used instead and this is stated explicitly in the evaluation.
8. Zones for coverage analysis, such as wards or grid cells, can be loaded as polygons, optionally carrying population.

---

# 12. Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Backend programming language |
| Spring Boot 3.x | Backend framework |
| Spring MVC | REST APIs |
| Spring Security | Authentication and authorization |
| JWT | Stateless authentication |
| Spring Data JPA | Persistence |
| Hibernate 6 | ORM implementation |
| Hibernate Spatial | Geometry mapping |
| PostgreSQL 16 | Primary relational database |
| PostGIS 3.4 | Geospatial storage and queries |
| JTS | Geometry objects in Java |
| Flyway | Database migrations |
| Maven | Dependency and build management |
| JUnit 5 | Testing |
| Mockito | Unit-test mocking |
| Testcontainers | Integration testing against real PostGIS |
| Micrometer / Prometheus | Metrics |
| springdoc-openapi | API documentation |
| Docker Compose | Local infrastructure |
| Git and GitHub | Version control and hosting |

---

# 13. Success Criteria

The project succeeds when, on a realistic dataset at 5,000+ bus scale:

```text
Every trip is covered by a block, or every uncovered trip is
explained by a recorded conflict.

Published schedules contain zero hard-constraint violations,
verified by an independent validator and by SQL invariant checks.

Unlinked scheduling needs fewer duties and less paid idle time
than linked scheduling and than the baseline, on the same input.

Schedule generation completes within the stated time targets,
replacing multi-day manual preparation.

Route overlap detection meets its precision and recall targets
on a labelled set of route pairs.

Every endpoint and role combination in the permission matrix is
covered by automated authorization tests.
```

Detailed metrics, baselines, datasets and acceptance thresholds are defined in the Evaluation document.

---

# 14. Related Documents

```text
Architecture.md    -> modules, data model, algorithms, security, APIs
Implementation.md  -> phase-wise build plan
Edge case.md       -> edge cases and how each is handled and tested
Evaluation.md      -> metrics, baselines, experiments, acceptance
```
