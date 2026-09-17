# Automated Bus Scheduling and Route Management System — Evaluation

## 1. Purpose

This document defines what "working" and "better" mean for the system, how each claim is measured, against which baseline, and what has to be true before the project can be called complete.

Every number below is a target or a threshold. None of them is a result.

Measured results go into the report template in section 14, after an actual evaluation run. A claim such as "reduced scheduling conflicts" is only made once a measured value backs it against a stated baseline.

## Principles

Hard constraints are pass or fail, never a score. A published schedule with even one hard violation fails, no matter how efficient it is.

Comparisons are made against a baseline on identical input. An improvement only means something relative to a baseline run on the same trips, fleet, crew and rules.

The checker is independent of the builder. Compliance is verified by the final constraint engine pass and separately by a SQL invariant suite that shares no code with the scheduling algorithms.

Every evaluation run is reproducible. It records dataset checksum, rule-set version, seed, build identifier, hardware and JVM settings.

Distributions are reported, not just averages. Latency as p50, p95 and p99, run time per depot as median and maximum, fairness as dispersion.

Where a lower bound can be computed, such as fleet size or duty count, results are reported as a gap to that bound rather than as a bare number.

---

# 2. Evaluation Dimensions

```text
D1  Constraint compliance    Are schedules legal and conflict-free?
D2  Schedule quality         How efficiently are buses and crew used?
D3  Automation and speed     How fast compared with manual, and does
                             it scale to 5,000+ buses?
D4  Geospatial correctness   Are overlap and coverage computed
                             correctly and quickly?
D5  API performance          Do list endpoints meet latency targets
                             under load?
D6  Security                 Is access control correct for every role,
                             endpoint and depot?
D7  Reliability              Does the system stay correct under
                             concurrency and failures?
D8  Engineering quality      Test coverage, edge-case coverage,
                             maintainability
```

---

# 3. Datasets

```text
DS-S     Small synthetic
         1 depot, 20 routes, ~120 buses, ~1,200 trips/day, ~300 crew
         Used for development, fixtures and fast CI evaluation.

DS-M     Medium synthetic
         10 depots, ~1,200 buses, ~12,000 trips/day, ~3,000 crew
         Used for algorithm comparisons and regression.

DS-L     Large synthetic
         45 depots, 5,000+ buses, ~50,000 trips/day, ~12,000 crew,
         7-day horizon
         Used for scale and performance validation.

DS-R     Realistic network
         Routes, stops, shapes and trips derived from a public static
         GTFS feed for Delhi, subject to licence, combined with
         synthetic fleet and crew.
         Used for real geometry and timetable structure.

DS-GEO   Labelled route pairs
         At least 200 route pairs labelled by a human reviewer.
         Used for overlap precision and recall.

DS-EDGE  Edge-case fixtures
         Small hand-built inputs, one per ID in the edge-case document.

DS-HIST  Manual baseline sample
         Historical manual schedules and rosters for a sample of
         depots and dates, if obtainable, with recorded
         post-publication incidents.
```

DS-GEO labels are:

```text
DUPLICATE
PARTIAL_OVERLAP   (with approximate overlapping km)
NO_OVERLAP
```

It deliberately includes tricky cases: parallel roads, crossings, loops and opposite directions.

## Synthetic data requirements

Synthetic data is generated from a fixed seed with a published checksum, so a result can always be traced back to the exact input.

The structure has to be realistic rather than uniform:

- Morning and evening peaks.
- Radial and ring routes.
- Terminals flagged as relief points.
- A mixed CNG and electric fleet.
- A leave rate of roughly 5 to 8 percent.
- A licence-expiry distribution that includes some already expired and some expiring soon.
- Some bus unavailability windows.

It also includes deliberately infeasible pockets, for example a 6-hour stretch with no relief point, or a vehicle class that the depot does not have. Without these, conflict detection is never exercised.

---

# 4. Baselines

```text
B0   Manual process
     Historical spreadsheet schedules from DS-HIST: preparation lead
     time, bus and duty counts, and conflicts discovered after
     publication.
     Used for automation and conflict-reduction claims.

B1   Naive rule of thumb
     Each trip chained first-fit onto buses. Every block covered by
     fixed linked 8-hour shifts cut at the nearest relief point, with
     no break optimisation. Crew assigned first-available.
     Imitates a simple manual approach.

B2   Greedy linked
     The system's own GreedyBestFitBlockBuilder, LinkedDutyBuilder
     and MrvCrewAssigner.
     Isolates the benefit of unlinked mode and local search.

LB   Lower bounds
     Fleet: minimum path cover via maximum matching.
     Duties: ceil(total platform time / max work per duty), a weak
     bound.
     Used to report the optimality gap.
```

If DS-HIST cannot be obtained, B0 is not claimed at all. Comparisons then use B1 and B2 only, and the report says so explicitly.

---

# 5. Schedule Quality Metrics

## 5.1 Vehicle schedule

Trip coverage is trips in blocks divided by timetabled trips. Higher is better. The target is 100%, or every uncovered trip carries a conflict with a reason.

Peak vehicle requirement is the maximum number of blocks active at the same time. Lower is better. The greedy result should be within 3% of the matching lower bound.

In-service ratio is total trip time divided by total time from pull-out to pull-in. Higher is better, and the target is at least 5 percentage points above B1.

Dead-km ratio is dead km divided by total km. Lower is better, and it must not exceed B1.

Mid-day depot returns, counted along with the dead km they add, are reported as context rather than scored.

EV range compliance must be 100%. Every electric block stays within usable range.

## 5.2 Crew schedule

```text
Duty count                  lower is better
                            target: unlinked <= B2 - 8% on DS-M

Duty gap to lower bound     (duties - LB) / LB
                            reported and tracked across releases

Platform-to-paid ratio      platform time / paid time
                            target: unlinked >= 0.80, and >= B2

Paid idle time              paid - platform - paid breaks
                                 - sign on/off - travel
                            target: unlinked <= B2 - 15%

Overtime                    sum of max(0, work - standard)
                            target: <= B1

Short-duty share            duties below the paid guarantee
                            target: unlinked <= 5%

Split-duty share            reported, policy-dependent

Bus changeovers per duty    mean and max
                            target: max within limit for >= 95%
                            of duties

Handover feasibility        gap >= transfer + buffer
                            target: 100%
```

## 5.3 Crew assignment

Assignment rate is assigned slots divided by required slots. The target is 100% whenever eligible supply meets demand. Where it does not, every unassigned slot must carry a reason histogram.

Weekly hours dispersion is the coefficient of variation of weekly hours across active crew. Lower is better, and it must not exceed B1.

Night-duty fairness is the Gini coefficient of night duties per crew over 28 days. The target is 0.25 or below.

Standby coverage is standby crew divided by planned duties, and should land within one percentage point of the configured standby pool.

Pair continuity, the share of duties keeping the preferred driver and conductor pair, is reported but not scored.

## 5.4 Automation and conflict reduction

Schedule lead time is the time from "timetable approved" to "schedule published" for a depot-day. Generation itself must be under 60 seconds per depot-day. The end-to-end lead time, including human review, is measured and reported against B0.

Hard conflicts in published schedules must be zero, counted by the independent validator and by the SQL invariants.

Conflicts caught before publication are reported by type. This is the number that shows the system is doing its job.

Post-publication conflicts, meaning incidents discovered on the day of operation such as double booking, rest violations or an invalid licence, should fall by at least 90% against B0 where B0 is available. Otherwise they are reported as an absolute number.

Manual overrides per duty are reported. A high rate is a signal that the rules or the model are missing something real.

Stability is the Jaccard similarity of duty-and-crew pairs between runs with a 1% input perturbation, for example 1% of trips shifted by 5 minutes. The target is 0.85 or above, because a schedule that changes completely after a tiny input change is unusable in practice.

Determinism is output hash equality for identical input and seed across 10 runs. The target is 100%.

---

# 6. Constraint Compliance

## 6.1 Method

Each published schedule is verified three separate ways.

The engine validator runs `ConstraintEngine.validate` as a full recomputation, not an incremental check.

Property-based tests generate random but valid inputs and assert invariants on the outputs.

The SQL invariant suite runs directly against the database after publish. Every query must return zero rows. It shares no code with the engine, which is the point.

## 6.2 SQL invariant suite

```sql
-- INV-01: no crew double booking among published assignments
SELECT a.crew_member_id, a.id, b.id
FROM duty_assignment a
JOIN duty_assignment b
  ON a.crew_member_id = b.crew_member_id AND a.id < b.id
 AND a.work_period && b.work_period
WHERE a.schedule_status = 'PUBLISHED' AND b.schedule_status = 'PUBLISHED'
  AND a.status <> 'CANCELLED' AND b.status <> 'CANCELLED';
```

```sql
-- INV-02: minimum rest between consecutive duties (example: 10 h)
SELECT *
FROM (
  SELECT crew_member_id, id,
         lower(work_period) - LAG(upper(work_period)) OVER w AS rest
  FROM duty_assignment
  WHERE schedule_status = 'PUBLISHED' AND status <> 'CANCELLED'
  WINDOW w AS (PARTITION BY crew_member_id ORDER BY lower(work_period))
) x
WHERE rest < interval '10 hours';
```

```sql
-- INV-03: rolling 7-day work does not exceed 48 h
SELECT *
FROM (
  SELECT a.crew_member_id, a.service_date,
         SUM(d.paid_sec - d.break_sec) OVER (
           PARTITION BY a.crew_member_id
           ORDER BY a.service_date
           RANGE BETWEEN INTERVAL '6 days' PRECEDING AND CURRENT ROW
         ) AS work_7d_sec
  FROM duty_assignment a
  JOIN duty d ON d.id = a.duty_id
  WHERE a.schedule_status = 'PUBLISHED' AND a.status <> 'CANCELLED'
) x
WHERE work_7d_sec > 48 * 3600;
```

```sql
-- INV-04: every timetabled trip covered exactly once, or explained
SELECT s.id AS schedule_id, t.id AS trip_id, COUNT(be.block_id) AS times_covered
FROM schedule s
JOIN trip t ON t.timetable_id IN (
        SELECT timetable_id FROM schedule_timetable WHERE schedule_id = s.id)
LEFT JOIN vehicle_block vb ON vb.schedule_id = s.id
LEFT JOIN block_event be ON be.block_id = vb.id AND be.trip_id = t.id
WHERE s.status = 'PUBLISHED'
GROUP BY s.id, t.id
HAVING COUNT(be.block_id) <> 1
   AND NOT EXISTS (
         SELECT 1 FROM conflict c
         WHERE c.schedule_id = s.id AND c.type = 'UNCOVERED_TRIP'
           AND (c.entity_refs ->> 'tripId')::bigint = t.id);
```

```sql
-- INV-05: licence valid on the service date for every published driver
SELECT a.id, c.employee_code, c.licence_expiry, a.service_date
FROM duty_assignment a
JOIN crew_member c ON c.id = a.crew_member_id
WHERE a.schedule_status = 'PUBLISHED' AND a.crew_role = 'DRIVER'
  AND (c.licence_expiry IS NULL OR c.licence_expiry < a.service_date);
```

```sql
-- INV-06: no assignment overlapping approved leave
SELECT a.id, l.crew_member_id
FROM duty_assignment a
JOIN crew_leave l
  ON l.crew_member_id = a.crew_member_id AND l.period && a.work_period
WHERE a.schedule_status = 'PUBLISHED' AND a.status <> 'CANCELLED';
```

```sql
-- INV-07: no bus double booking or use during unavailability
SELECT ba.id, bu.bus_id
FROM bus_assignment ba
JOIN bus_unavailability bu
  ON bu.bus_id = ba.bus_id AND bu.period && ba.period
WHERE ba.schedule_status = 'PUBLISHED';
```

```sql
-- INV-08: each piece of work belongs to exactly one duty
SELECT p.id
FROM piece_of_work p
JOIN vehicle_block vb ON vb.id = p.block_id
JOIN schedule s ON s.id = vb.schedule_id AND s.status = 'PUBLISHED'
LEFT JOIN duty_piece dp ON dp.piece_id = p.id
GROUP BY p.id
HAVING COUNT(dp.duty_id) <> 1;
```

```sql
-- INV-09: at most one published schedule per depot-day
SELECT depot_id, service_date, COUNT(*)
FROM schedule
WHERE status = 'PUBLISHED'
GROUP BY depot_id, service_date
HAVING COUNT(*) > 1;
```

Two notes on these queries.

In INV-03, the work-time expression `paid_sec - break_sec` must match the policy's definition of work, in particular whether paid breaks count towards the weekly limit.

INV-04 assumes a `schedule_timetable` link table recording which timetables a run used.

## 6.3 Pass criteria

```text
Hard violations from the engine validator on published
schedules, across DS-S, DS-M, DS-L and DS-R       0

SQL invariants INV-01 to INV-09                   0 rows each

Property tests, at least 1,000 tries per property 100% pass

Draft conflicts carrying type, entity reference
and message                                       100%

Injected infeasibilities detected as the correct
conflict type                                     100%
```

---

# 7. Algorithm Experiments

## 7.1 Ablation matrix

Each configuration runs on DS-S, DS-M and DS-R with 5 seeds, reporting median and range.

```text
A0 = B1   first-fit blocks   naive fixed 8 h linked   no search
          first-available crew

A1 = B2   greedy best-fit    linked                   no search
          MRV + fairness

A2        matching           linked                   no search
          MRV + fairness

A3        greedy best-fit    unlinked (greedy)        no search
          MRV + fairness

A4        greedy best-fit    unlinked                 10 s search
          MRV + fairness

A5        greedy best-fit    unlinked                 30 s search
          MRV + fairness

A6        matching           unlinked                 30 s search
          MRV + fairness

A7        greedy best-fit    unlinked                 30 s search
          first-available crew
```

Each comparison answers one question:

```text
A1 vs A2       How far is greedy from the minimum fleet, and is the
               matching builder worth its extra cost?

A1 vs A3       How much do unlinked duties save over linked duties?

A3 / A4 / A5   What does local search add, and where do returns
               start diminishing?

A5 vs A7       Does MRV plus fairness improve assignment rate and
               fairness without hurting legality?

A0 vs best     Overall improvement over a naive approach.
```

## 7.2 Sensitivity analysis

```text
minRestBetweenDutiesMin    480, 600, 720
  -> assignment rate, unassigned duties

maxContinuousWorkMin       240, 300
  -> duty count, NO_FEASIBLE_RELIEF count

Relief-point density       current, -30%, +30%
  -> duty count, platform-to-paid ratio, handovers

maxBusChangeoversPerDuty   1, 2, 3
  -> duty count against changeovers

EV share of fleet          10%, 30%, 60%
  -> PVR, charging conflicts, dead km

Local search budget        0, 5, 10, 30, 60 s
  -> cost curve and diminishing returns
```

This analysis is what shows planners and management the operational cost of a policy choice, for example how many extra duties a longer minimum rest actually requires.

## 7.3 Statistical reporting

Report the median and the minimum to maximum range over 5 seeds, using paired comparisons on the same dataset and seed.

A claimed improvement must hold for every seed on DS-M, not merely on average.

---

# 8. Geospatial Evaluation

## 8.1 Overlap detection accuracy

Ground truth comes from the DS-GEO labelled pairs. A pair is predicted as duplicate or overlapping when the overlap ratio exceeds a threshold.

```text
Precision (overlap >= MEDIUM)     >= 0.90
Recall (overlap >= MEDIUM)        >= 0.90
F1                                >= 0.90
Overlap length error              median <= 10%
Direction classification accuracy >= 95%
Junction false positives          0
```

Overlap length error is measured on partial-overlap pairs as the absolute difference between predicted and labelled overlap kilometres, relative to the labelled value.

A buffer sensitivity sweep runs the analysis across a grid of settings:

```text
bufferM       10, 15, 25, 40, 60
minSegmentM   100, 200, 400
```

Precision and recall are plotted for each setting, and the chosen defaults of 25 m and 200 m are justified against that plot, in particular on the parallel-road cases.

## 8.2 Geometry fixtures

```text
Straight 1 km line in Delhi        length_m = 1,000 +/- 1 m
Identical patterns                 ratio 1.00 +/- 0.01
3 km shared on a 10 km route       ratio 0.30 +/- 0.01
Parallel line 40 m away, 25 m buf  ratio 0.00
Perpendicular crossing             ratio 0.00
Out-and-back on the same road      ratio <= 1.00, length not doubled
Loop route                         detected closed, direction LOOP
Swapped lat/lon input              rejected with a swap hint
```

## 8.3 Coverage accuracy

Zone coverage ratios are compared with an independent GIS computation, for example a QGIS buffer and intersect, on 20 zones. The absolute difference must stay within 2 percentage points per zone.

Grid resolution effect is checked by running the same zones at 100 m and 250 m cells. The 250 m grid must stay within 3 percentage points of the 100 m grid, otherwise the cheaper grid is not trustworthy.

Coverage gain of a proposal is compared with a manual computation for 5 proposals, with a relative error of 3% or less.

## 8.4 Geospatial performance

```text
Ad-hoc overlap analysis
  proposal <= 2,000 vertices against the full network
  (~2,000 patterns on DS-R / DS-L)              p95 < 500 ms

Same query with the spatial index dropped
  (experiment only)                             recorded, to show
                                                the speed-up factor

Stops in a bounding box (paginated)             p95 < 100 ms
Routes passing within 500 m of a point          p95 < 200 ms
Zone coverage report, all wards                 p95 < 2 s
Coverage view refresh after a stop edit         < 60 s on DS-L
```

Every query is captured with `EXPLAIN (ANALYZE, BUFFERS)` and the plan is attached to the report. A latency number without a plan does not explain itself.

---

# 9. API and Performance Evaluation

## 9.1 Environment

The application runs on one instance with 4 vCPU and 8 GB RAM for the baseline, then two instances for the scaling check.

The database is PostgreSQL 16 with PostGIS 3.4 on 4 vCPU, 16 GB RAM and SSD storage, loaded with DS-L plus 30 days of published schedules.

The load generator runs on a separate machine. Each test warms up for 2 minutes and then holds steady state for 10 minutes.

## 9.2 Load profile

```text
35%   filtered paginated lists
      /buses, /crew, /schedules/{id}/duties?unassigned=true

15%   keyset lists
      /trips, /audit-logs

15%   detail reads
      /routes/{id}, /schedule-runs/{id}

10%   reports and dashboard
      /reports/fleet-utilization, /dashboard/today

10%   writes
      leave records, bus status, assignment overrides

5%    spatial queries
      /stops?bbox=, /routes?passesNear=

5%    overlap analysis
      POST /routes/overlap-analysis

5%    auth
      login and refresh
```

## 9.3 API targets

```text
Paginated list endpoints, p95 at 50 users     < 200 ms
Paginated list endpoints, p99 at 50 users     < 500 ms
Detail endpoints, p95                         < 100 ms
Reports and dashboard, p95                    < 1 s
Overlap analysis, p95                         < 500 ms
Error rate excluding 4xx                      < 0.1%
Throughput at the targets above               >= 150 req/s
Horizontal scaling, 2 instances               >= 1.7x throughput
                                              at the same p95
Connection pool waits at target load          none over 50 ms
```

Two comparisons are recorded rather than scored.

Deep pagination is tested at page 500 with size 100, offset against keyset cursor. The keyset p95 must stay under 200 ms, and the offset degradation is recorded as evidence for the design choice.

The speed-up from `includeTotal=false` against a full count query on 30 days of `duty_assignment` is also recorded.

## 9.4 Engine performance

```text
Single depot-day, DS-S (~120 buses), linked        < 5 s
Single depot-day, DS-S, unlinked + 30 s search     < 40 s
Largest depot in DS-L, unlinked + 30 s search      < 60 s
Full DS-L, all depots, one service date, 8 vCPU    < 15 min
Full DS-L, 7-day horizon                           < 2 h
Memory per depot run                               < 512 MB heap
Persist results per depot-day (JDBC batch)         < 5 s
```

Measurements come from Micrometer timers, including per-phase timers, and from JFR recordings when a hotspot needs investigating.

---

# 10. Security Evaluation

## 10.1 Authorization matrix testing

A parameterised integration test iterates every endpoint against every role, in four contexts:

```text
own depot
other depot
HQ user (no depot binding)
unauthenticated
```

Expectations come from the permission matrix in the architecture document.

```text
allowed              2xx
unauthenticated      401
wrong role           403
out of depot scope   404
```

Targets:

```text
Endpoint and role cells covered by tests        100%
Tests passing                                   100%
Endpoints in OpenAPI missing from the matrix    0
```

The last one matters most. The test fails when a new endpoint is added and not classified, which is what stops the matrix from silently going out of date.

## 10.2 OWASP API Security Top 10 checklist

```text
API1  Cross-depot id access on every {id} endpoint
      -> 404 for all

API2  Expired, tampered, alg=none and HS256-confusion tokens;
      refresh reuse; brute force
      -> all rejected, lockout triggers, token family revoked

API3  Mass-assignment payloads (status, version, depotId);
      PII fields requested by the wrong role
      -> ignored or rejected, PII masked

API4  size=100000, 50k-vertex geometry, 50 MB body,
      run-creation flood
      -> clamped, 413 or 422, 429

API5  SCHEDULER publish, PLANNER override, MANAGER user admin
      -> 403

API6  Repeated publish or run creation
      -> idempotent or 409, rate limited

API7  No endpoint fetches user-supplied URLs
      -> confirmed by design review

API8  Actuator exposure, CORS, headers, stack traces
      -> only health and info public, strict CORS,
         no stack traces in responses

API9  Unversioned or undocumented endpoints
      -> all under /api/v1 and present in OpenAPI

API10 GTFS and CSV imports treated as untrusted
      -> validated, size-limited, parameterised
```

## 10.3 Scans

```text
Dependency vulnerability scan   0 critical, 0 high without an
                                accepted, recorded exception

OWASP ZAP API scan              0 high; medium findings triaged
(OpenAPI-driven, authenticated)

Secret scan of the source tree  0 findings
```

---

# 11. Reliability and Consistency Evaluation

## Concurrent run creation

Send 20 parallel run requests for the same depot and date.

Exactly one run is created. The others return 409 or an idempotent replay.

## Multi-instance claiming

Run two instances against 50 queued runs.

Each run is processed exactly once.

## Worker crash

Kill an instance mid-run.

The run is marked FAILED within 2.5 minutes, no partial schedule rows exist, and a re-run succeeds.

## Concurrent overrides

Two clients override overlapping assignments for the same crew member.

At most one succeeds, and no overlap survives to publication, which INV-01 confirms.

## Publish race

Trigger a publish and a revalidation job simultaneously.

They serialize, and the final state is consistent with the recorded conflicts.

## Database guard

Insert an overlapping published assignment directly with SQL, bypassing the application entirely.

The exclusion constraint rejects it with `23P01`. This is the test that proves the last line of defence actually exists.

## Idempotent publish

Publish twice with the same idempotency key. The same response comes back and only one version exists.

## Backup and restore

Restore the latest backup to a fresh instance.

The application starts and invariants INV-01 to INV-09 all return zero rows.

## Long soak

Run 4 hours at 50% of target load.

No memory growth trend, no connection leaks, and an error rate below 0.1%.

---

# 12. Test Strategy and Coverage

## 12.1 Test pyramid

```text
Unit             JUnit 5, AssertJ
                 engine algorithms, each constraint, value objects,
                 normalisers
                 target: >= 85% lines on scheduling.engine,
                 >= 80% overall domain

Property-based   jqwik
                 engine invariants
                 target: >= 1,000 tries per property, 100% pass

Mutation         PIT on constraint classes
                 target: mutation score >= 70%

Integration      Spring Boot Test, Testcontainers postgis:16-3.4
                 repositories, native spatial SQL, migrations,
                 publish flow, exclusion constraints

Architecture     ArchUnit
                 module boundaries, engine purity
                 target: 0 violations

Security         Spring Security Test, matrix tests
                 target: 100% of the matrix

Contract         OpenAPI response validation in integration tests
                 target: 100% of documented endpoints

Performance      k6 or Gatling, JMH for engine micro-benchmarks
```

Mutation testing is applied to the constraint classes specifically, because those are the classes where a test can pass while checking nothing.

## 12.2 Engine properties

```text
PROP-01  Every trip is in exactly one block, or in the uncovered
         list with a reason.

PROP-02  Within a block, each consecutive trip pair satisfies
         layover and deadhead time.

PROP-03  EV blocks never exceed usable range between charging
         events.

PROP-04  Every piece of work belongs to exactly one duty, and the
         union of pieces equals the block work.

PROP-05  Every duty emitted without a hard conflict satisfies all
         hard duty constraints.

PROP-06  Every handover gap is at least transfer time plus buffer.

PROP-07  No crew member is booked on overlapping work periods
         within a run.

PROP-08  Rest between consecutive assignments, including history,
         meets the minimum, or the slot is unassigned.

PROP-09  Local search output cost is no higher than input cost,
         with no new hard violations.

PROP-10  Same input and same seed produce an identical output hash.

PROP-11  Tightening any hard limit never produces a schedule that
         violates the tighter limit. It can only increase conflicts
         or duties.
```

## 12.3 Edge-case coverage

Every edge-case ID in sections 1 to 15 of the edge-case document must have at least one tagged passing test. The target is 100%.

Every ID in section 16, which covers operational cases outside the first version's scope, must have documented behaviour. The target is also 100%.

The CI report lists every `EC-*` ID with its test status, and missing IDs are reported as gaps rather than quietly ignored.

---

# 13. Acceptance Criteria per Phase

```text
P0  Foundations
    Clean build with Testcontainers. Migrations apply to an empty
    database. The ArchUnit engine-purity rule passes.

P1  Security
    Authentication tests pass. The matrix test skeleton is in place.
    Login is audited.

P2  Master data
    Pagination edge cases EC-API-01 to EC-API-10 pass. Cross-depot
    tests pass. Filtered queries use indexes.

P3  Routes and GIS
    Geometry fixtures pass. Overlap p95 under 500 ms. Proposal
    workflow tests pass.

P4  Timetables
    Trip counts match analytical counts. DS-S, DS-M and DS-L
    generate deterministically, verified by checksum.

P5  Blocks
    PROP-01 to PROP-03 pass. DS-S coverage is 100% or explained.
    The greedy PVR gap against matching is reported.

P6  Linked duties
    PROP-05 passes. A DS-S linked run has no unexplained hard
    violations. Constraint boundary tests pass.

P7  Unlinked duties
    PROP-04, PROP-06, PROP-09 and PROP-10 pass. The A3 and A5
    comparison against A1 is recorded on DS-M.

P8  Assignment and publish
    PROP-07 and PROP-08 pass. SQL invariants return zero rows on
    DS-L. Concurrency tests pass.

P9  Reporting
    Report figures equal an independent recomputation on DS-S.
    The audit completeness test passes.

P10 Hardening
    Performance targets met, or deviations documented. Scans clean.
    A restore rehearsal succeeds.
```

Final acceptance for release 1.0 requires all phase criteria met, plus the compliance criteria in section 6.3 on DS-L and DS-R, plus the section 5 quality targets for unlinked mode against B2 on DS-M.

---

# 14. Results Reporting Template

Each evaluation run produces a report with the sections below. Values are filled in only from measured runs.

## 14.1 Run metadata

| Field | Value |
|---|---|
| Date | |
| Application version | |
| Datasets and checksums | |
| Rule-set id and version | |
| Seeds | |
| Hardware (app and DB) | |
| JVM and DB settings | |

## 14.2 Compliance summary

| Dataset | Mode | Hard violations | SQL invariant rows | Property tests | Pass |
|---|---|---|---|---|---|
| DS-S | Linked | | | | |
| DS-S | Unlinked | | | | |
| DS-M | Unlinked | | | | |
| DS-L | Unlinked | | | | |
| DS-R | Unlinked | | | | |

## 14.3 Quality comparison

DS-M, median of 5 seeds.

| Metric | B1 | A1 (B2) | A3 | A5 | Lower bound | Target | Pass |
|---|---|---|---|---|---|---|---|
| Trip coverage | | | | | - | 100% | |
| PVR | | | | | | gap <= 3% | |
| Dead-km ratio | | | | | - | <= B1 | |
| Duties | | | | | | <= A1 - 8% | |
| Platform-to-paid | | | | | - | >= 0.80 | |
| Paid idle hours | | | | | - | <= A1 - 15% | |
| Overtime hours | | | | | - | <= B1 | |
| Assignment rate | | | | | - | 100% or explained | |
| Night-duty Gini | | | | | - | <= 0.25 | |

## 14.4 Performance

| Scenario | p50 | p95 | p99 | Throughput | Target | Pass |
|---|---|---|---|---|---|---|
| List endpoints | | | | | p95 < 200 ms | |
| Overlap analysis | | | | | p95 < 500 ms | |
| Depot-day run, largest | | | - | - | < 60 s | |
| Full fleet run, DS-L | - | - | - | - | < 15 min | |

## 14.5 Geospatial accuracy

| Buffer / min segment | Precision | Recall | F1 | Median length error | Direction accuracy |
|---|---|---|---|---|---|
| 25 m / 200 m (default) | | | | | |
| 15 m / 200 m | | | | | |
| 40 m / 200 m | | | | | |

## 14.6 Baseline comparison

Only filled in if DS-HIST is available.

| Metric | Manual (B0) | System | Change |
|---|---|---|---|
| Lead time per depot-day | | | |
| Post-publication conflicts per 1,000 duties | | | |
| Duties per 100 buses | | | |
| Peak vehicle requirement | | | |

## 14.7 Findings and actions

| Finding | Severity | Root cause | Action | Owner |
|---|---|---|---|---|
| | | | | |

---

# 15. Traceability

Each objective from the project statement maps to specific metrics and to the evidence that produces them.

```text
O1  Automate linked and unlinked duty scheduling for 5,000+ buses
    Metrics: trip coverage, DS-L full-fleet run time, lead time
             against B0, both modes runnable through /schedule-runs
    Evidence: engine benchmark, run metrics, API integration tests

O2  Model crew-bus assignments, handovers and rest constraints
    Metrics: zero hard violations, SQL invariants, 100% handover
             feasibility, post-publication conflicts against B0,
             PVR gap, duties and platform-to-paid against B1 and B2
    Evidence: compliance suite, ablation results

O3  Geospatial route management with overlap and coverage
    Metrics: overlap precision, recall and F1, length error,
             coverage accuracy, spatial query p95 with and without
             the index
    Evidence: DS-GEO evaluation, geometry fixtures, EXPLAIN plans

O4  RBAC, pagination and filtering, real-time data and reporting
    Metrics: 100% authorization matrix, endpoint inventory from
             OpenAPI (48 endpoints, 22 paginated and filterable),
             list p95, dashboard freshness (asOf within 60 s)
    Evidence: security tests, OpenAPI export, load test report

O5  Traceable and auditable changes
    Metrics: audit completeness across all write endpoints,
             append-only enforcement
    Evidence: audit completeness test, database privilege test
```

---

# 16. Threats to Validity

Every evaluation has limits, and stating them is part of the result.

## Synthetic data may not reflect real operations

Headways, relief-point density and leave patterns in synthetic data are guesses. Quality and performance results may not transfer.

DS-R is included precisely for this reason, since it carries real network geometry and trips. No claim about production impact is made before validation against real DTC data.

## Labour-rule values are assumptions

Duty counts and assignment rates depend strongly on them.

The sensitivity analysis in section 7.2 exists to show how strongly. Rule sets must be reviewed with DTC before any production evaluation.

## Baseline B0 may be unavailable

If manual data cannot be obtained, conflict-reduction and lead-time claims cannot be quantified against reality.

In that case no B0 comparison is claimed. Results are reported against B1 and B2, and the report states the limitation plainly.

## Labelled overlap pairs reflect one reviewer's judgement

This biases precision and recall.

A subset is labelled independently by two reviewers, agreement is reported as Cohen's kappa, and disagreements are resolved and documented.

## The duty lower bound is weak

The gap therefore overstates sub-optimality.

It is labelled as an upper estimate of the gap. An exact solver on DS-S would give a tighter comparison and is worth doing if time allows.

## The performance environment differs from production

Latency targets may not hold elsewhere.

The environment is recorded with every result, and a smoke benchmark is re-run on production-like infrastructure before go-live.

## Heuristic randomness invites cherry-picking

A fixed seed set is used, every seed is reported, and an improvement must hold across all of them to be claimed.
