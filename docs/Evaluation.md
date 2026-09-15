# Evaluation — How the System Is Measured and Accepted

> Related: [Project statement](Project%20statement.md) · [Architecture](Architecture.md) · [Implementation](Implementation.md) · [Edge cases](Edge%20case.md)

This document defines **what "working" and "better" mean** for the system, **how each claim is measured**, **against which baseline**, and **what thresholds must be met** for acceptance.

> **Important:** all numeric values below are **targets or thresholds**, not results. Measured results are recorded in the results template (§14) after each evaluation run. A claim (e.g. "reduced scheduling conflicts") is only made once it is backed by a measured value against a stated baseline.

---

## 1. Principles

1. **Hard constraints are pass/fail, not a score.** A published schedule with even one hard violation fails, regardless of efficiency.
2. **Compare against a baseline on identical input.** Improvements are only meaningful relative to a baseline run on the same trips, fleet, crew and rules.
3. **The checker is independent of the builder.** Compliance is verified by the final `ConstraintEngine` pass **and** by a separate SQL invariant suite that does not share code with the scheduling algorithms.
4. **Reproducible.** Every evaluation run records dataset checksum, rule-set version, seed, git-independent build identifier (artifact version), hardware and JVM settings.
5. **Report distributions, not just averages.** Latency as p50/p95/p99, run time per depot as median and max, fairness as dispersion.
6. **Know the optimality gap.** Where a lower bound is computable (fleet size, duty count), results are reported as a gap to that bound.

---

## 2. Evaluation dimensions

| # | Dimension | Question answered | Section |
|---|---|---|---|
| D1 | Constraint compliance | Are schedules legal and conflict-free? | §6 |
| D2 | Schedule quality | How efficiently are buses and crew used? | §5, §7 |
| D3 | Automation and speed | How fast compared with the manual process, and does it scale to 5,000+ buses? | §5.4, §9.3 |
| D4 | Geospatial correctness | Are overlaps and coverage computed correctly and quickly? | §8 |
| D5 | API performance | Do paginated or filtered endpoints meet latency targets under load? | §9 |
| D6 | Security | Is access control correct for every role, endpoint and depot? | §10 |
| D7 | Reliability and consistency | Does the system stay correct under concurrency and failures? | §11 |
| D8 | Engineering quality | Test coverage, edge-case coverage, maintainability | §12 |

---

## 3. Datasets

| ID | Name | Composition | Purpose |
|---|---|---|---|
| **DS-S** | Small synthetic | 1 depot, 20 routes, ~120 buses, ~1,200 trips/day, ~300 crew | Development, fixtures, fast CI evaluation |
| **DS-M** | Medium synthetic | 10 depots, ~1,200 buses, ~12,000 trips/day, ~3,000 crew | Algorithm comparisons, regression |
| **DS-L** | Large synthetic | 45 depots, **5,000+ buses**, ~50,000 trips/day, ~12,000 crew, 7-day horizon | Scale and performance validation (O1) |
| **DS-R** | Realistic network | Routes, stops, shapes and trips derived from a public static GTFS feed for Delhi (subject to licence), combined with synthetic fleet and crew | Real geometry and timetable structure |
| **DS-GEO** | Labelled route pairs | ≥ 200 route pairs labelled by a human reviewer as `DUPLICATE`, `PARTIAL_OVERLAP` (with approximate overlapping km) or `NO_OVERLAP`, including tricky cases (parallel roads, crossings, loops, opposite directions) | Overlap precision and recall (§8) |
| **DS-EDGE** | Edge-case fixtures | Small hand-built inputs, one per ID in [Edge case.md](Edge%20case.md) | Correctness of every edge case |
| **DS-HIST** | Manual baseline sample | Historical manual schedules and rosters for a sample of depots and dates (if obtainable), with recorded post-publication incidents | Baseline B0 (§4) |

**Synthetic data requirements:**
- Generated from a fixed seed with a published checksum.
- Realistic structure: morning and evening peaks, radial and ring routes, terminals as relief points, mixed CNG/EV share, leave rate around 5–8 %, a licence-expiry distribution that includes some expired or expiring licences, and some bus unavailability windows.
- Includes deliberately **infeasible pockets** (e.g. a 6-hour stretch with no relief point, a class of vehicle not present) so conflict detection is exercised.

---

## 4. Baselines

| ID | Baseline | Description | Used for |
|---|---|---|---|
| **B0** | Manual process | Historical spreadsheet schedules (DS-HIST): preparation lead time, bus and duty counts, conflicts discovered after publication. **If this data cannot be obtained, B0 is not claimed**, and comparisons use B1 and B2 only (stated explicitly in the report). | Automation and conflict-reduction claims |
| **B1** | Naive rule-of-thumb | Each trip chained first-fit onto buses. Every block covered by fixed linked shifts of 8 h cut at the nearest relief point, with no break optimisation. Crew assigned first-available. This imitates a simple manual approach. | Quality improvement over a simple approach |
| **B2** | Greedy linked | The system's own `GreedyBestFitBlockBuilder` + `LinkedDutyBuilder` + `MrvCrewAssigner` | Isolates the benefit of unlinked mode and local search |
| **LB** | Lower bounds | Fleet: min path cover via maximum matching. Duties: ⌈Σ platform time ÷ max work per duty⌉ (weak bound). | Optimality gap |

---

## 5. Schedule quality metrics

### 5.1 Vehicle schedule

| Metric | Formula | Better | Target |
|---|---|---|---|
| Trip coverage | trips in blocks ÷ timetabled trips | ↑ | **100 %**, or all uncovered trips carry a conflict with a reason |
| Peak vehicle requirement (PVR) | max over t of blocks active at t | ↓ | Greedy gap to matching lower bound ≤ **3 %** |
| In-service ratio | Σ trip time ÷ Σ (pull-in − pull-out) | ↑ | ≥ B1 + 5 percentage points |
| Dead-km ratio | dead km ÷ (service km + dead km) | ↓ | ≤ B1 |
| Midday depot returns | count and dead km added | context | Reported |
| EV range compliance | EV blocks within usable range | = | **100 %** |

### 5.2 Crew schedule

| Metric | Formula | Better | Target |
|---|---|---|---|
| Duty count | number of duties | ↓ | Unlinked ≤ linked (B2) − **8 %** on DS-M |
| Duty gap to lower bound | (duties − LB) ÷ LB | ↓ | Reported. Trend tracked across releases. |
| Platform-to-paid ratio | Σ platform time ÷ Σ paid time | ↑ | Unlinked ≥ **0.80**, and ≥ B2 |
| Paid idle time | Σ (paid − platform − paid breaks − sign-on/off − travel) | ↓ | Unlinked ≤ B2 − **15 %** |
| Overtime | Σ max(0, work − standard) | ↓ | ≤ B1 |
| Short-duty share | duties with paid < min guarantee ÷ duties | ↓ | Unlinked ≤ **5 %** |
| Split-duty share | split duties ÷ duties | context | Reported (policy-dependent) |
| Bus changeovers per duty (unlinked) | mean and max | ↓ | Max ≤ `maxBusChangeoversPerDuty` for ≥ **95 %** of duties |
| Handover feasibility | handovers with gap ≥ transfer + buffer | = | **100 %** |

### 5.3 Crew assignment (roster)

| Metric | Formula | Better | Target |
|---|---|---|---|
| Assignment rate | assigned slots ÷ required slots | ↑ | **100 %** when eligible supply ≥ demand. Otherwise every unassigned slot has a reason histogram. |
| Weekly hours dispersion | coefficient of variation of weekly hours across active crew | ↓ | ≤ B1 |
| Night-duty fairness | Gini coefficient of night duties per crew over 28 days | ↓ | ≤ **0.25** |
| Standby coverage | standby crew ÷ planned duties | = | Within ±1 pp of `standbyPoolPct` |
| Pair continuity (optional) | share of duties keeping the preferred driver–conductor pair | ↑ | Reported |

### 5.4 Automation and conflict reduction

| Metric | Definition | Baseline | Target |
|---|---|---|---|
| Schedule lead time | Time from "timetable approved" to "schedule published" for a depot-day | B0 (manual) | Generation < **60 s** per depot-day. End-to-end lead time (including human review) measured and reported vs B0. |
| Hard conflicts in published schedules | Count from independent validator + SQL invariants | B0, B1 | **0** |
| Conflicts caught before publication | Conflicts detected by the system during draft/validation | – | Reported by type |
| Post-publication conflicts | Incidents discovered on the day of operation (double booking, rest violation, invalid licence) | B0 | ≥ **90 % reduction** vs B0 if B0 is available. Otherwise reported as an absolute number. |
| Manual overrides per schedule | Overrides ÷ duties | – | Reported (a high rate signals rule or model gaps) |
| Stability | Jaccard similarity of (duty, crew) pairs between runs with a 1 % input perturbation (e.g. 1 % trips shifted by 5 min) | – | ≥ **0.85** |
| Determinism | Output hash equality for identical input + seed across 10 runs | – | **100 %** |

---

## 6. Constraint compliance (D1)

### 6.1 Method

Each published schedule is verified three ways:

1. **Engine validator:** `ConstraintEngine.validate` performs a full recomputation, not an incremental check.
2. **Property-based tests (jqwik):** random but valid inputs are generated, and invariants are asserted on outputs (see §12.2).
3. **SQL invariant suite:** it runs directly on the database after publish and **every query must return zero rows**.

### 6.2 SQL invariant suite

```sql
-- INV-01: no crew double booking among published assignments
SELECT a.crew_member_id, a.id, b.id
FROM duty_assignment a
JOIN duty_assignment b
  ON a.crew_member_id = b.crew_member_id AND a.id < b.id
 AND a.work_period && b.work_period
WHERE a.schedule_status = 'PUBLISHED' AND b.schedule_status = 'PUBLISHED'
  AND a.status <> 'CANCELLED' AND b.status <> 'CANCELLED';

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

-- INV-03: rolling 7-day work ≤ 48 h
SELECT *
FROM (
  SELECT a.crew_member_id, a.service_date,
         SUM(d.paid_sec - d.break_sec) OVER (
           PARTITION BY a.crew_member_id
           ORDER BY a.service_date
           RANGE BETWEEN INTERVAL '6 days' PRECEDING AND CURRENT ROW) AS work_7d_sec
  FROM duty_assignment a
  JOIN duty d ON d.id = a.duty_id
  WHERE a.schedule_status = 'PUBLISHED' AND a.status <> 'CANCELLED'
) x
WHERE work_7d_sec > 48 * 3600;

-- INV-04: every timetabled trip covered exactly once in each published schedule
SELECT s.id AS schedule_id, t.id AS trip_id, COUNT(be.block_id) AS times_covered
FROM schedule s
JOIN trip t ON t.timetable_id IN (SELECT timetable_id FROM schedule_timetable WHERE schedule_id = s.id)
LEFT JOIN vehicle_block vb ON vb.schedule_id = s.id
LEFT JOIN block_event be ON be.block_id = vb.id AND be.trip_id = t.id
WHERE s.status = 'PUBLISHED'
GROUP BY s.id, t.id
HAVING COUNT(be.block_id) <> 1
   AND NOT EXISTS (SELECT 1 FROM conflict c
                   WHERE c.schedule_id = s.id AND c.type = 'UNCOVERED_TRIP'
                     AND (c.entity_refs ->> 'tripId')::bigint = t.id);

-- INV-05: licence valid on the service date for every published driver assignment
SELECT a.id, c.employee_code, c.licence_expiry, a.service_date
FROM duty_assignment a
JOIN crew_member c ON c.id = a.crew_member_id
WHERE a.schedule_status = 'PUBLISHED' AND a.crew_role = 'DRIVER'
  AND (c.licence_expiry IS NULL OR c.licence_expiry < a.service_date);

-- INV-06: no assignment overlapping approved leave
SELECT a.id, l.crew_member_id
FROM duty_assignment a
JOIN crew_leave l ON l.crew_member_id = a.crew_member_id AND l.period && a.work_period
WHERE a.schedule_status = 'PUBLISHED' AND a.status <> 'CANCELLED';

-- INV-07: no bus double booking or use during unavailability
SELECT ba.id, bu.bus_id
FROM bus_assignment ba
JOIN bus_unavailability bu ON bu.bus_id = ba.bus_id AND bu.period && ba.period
WHERE ba.schedule_status = 'PUBLISHED';

-- INV-08: each piece of work belongs to exactly one duty
SELECT p.id
FROM piece_of_work p
JOIN vehicle_block vb ON vb.id = p.block_id
JOIN schedule s ON s.id = vb.schedule_id AND s.status = 'PUBLISHED'
LEFT JOIN duty_piece dp ON dp.piece_id = p.id
GROUP BY p.id
HAVING COUNT(dp.duty_id) <> 1;

-- INV-09: at most one published schedule per depot-day
SELECT depot_id, service_date, COUNT(*)
FROM schedule WHERE status = 'PUBLISHED'
GROUP BY depot_id, service_date HAVING COUNT(*) > 1;
```

> In INV-03, adjust the work-time expression (`paid_sec - break_sec`) to the policy's definition of "work" (e.g. whether paid breaks count). INV-04 assumes a `schedule_timetable` link table recording the timetables used by a run.

### 6.3 Pass criteria

| Check | Threshold |
|---|---|
| Hard violations (engine validator) in published schedules, across DS-S, DS-M, DS-L and DS-R | **0** |
| SQL invariants INV-01 to INV-09 | **0 rows** each |
| Property tests (≥ 1,000 tries per property) | **100 % pass** |
| Conflicts in draft schedules explained with type, entity reference and message | **100 %** |
| Injected infeasibilities in synthetic data detected as the correct conflict type | **100 %** |

---

## 7. Algorithm experiments

### 7.1 Ablation matrix

Run on DS-S, DS-M and DS-R, with 5 seeds each, reporting median and range.

| Config | Blocks | Duties | Search | Crew |
|---|---|---|---|---|
| A0 = B1 | First-fit | Naive fixed 8 h linked | – | First available |
| A1 = B2 | Greedy best-fit | Linked | – | MRV + fairness |
| A2 | Matching (min fleet) | Linked | – | MRV + fairness |
| A3 | Greedy best-fit | Unlinked (greedy) | – | MRV + fairness |
| A4 | Greedy best-fit | Unlinked | Local search 10 s | MRV + fairness |
| A5 | Greedy best-fit | Unlinked | Local search 30 s | MRV + fairness |
| A6 | Matching (min fleet) | Unlinked | Local search 30 s | MRV + fairness |
| A7 | A5 with first-available crew | Unlinked | Local search 30 s | First available |

**Questions each comparison answers:**

| Comparison | Question |
|---|---|
| A1 vs A2 | How far is greedy from the minimum fleet? Is the matching builder worth its extra cost? |
| A1 vs A3 | How much do unlinked duties save over linked duties? |
| A3 vs A4 vs A5 | What does local search add, and where do returns diminish? |
| A5 vs A7 | Does MRV + fairness improve assignment rate and fairness without hurting legality? |
| A0 vs best | Overall improvement over a naive approach |

### 7.2 Sensitivity analysis

| Parameter varied | Values | Observed metrics |
|---|---|---|
| `minRestBetweenDutiesMin` | 480, 600, 720 | Assignment rate, unassigned duties |
| `maxContinuousWorkMin` | 240, 300 | Duty count, `NO_FEASIBLE_RELIEF` count |
| Relief-point density | Current, −30 %, +30 % | Duty count, platform-to-paid ratio, handovers |
| `maxBusChangeoversPerDuty` | 1, 2, 3 | Duty count vs changeovers trade-off |
| EV share of fleet | 10 %, 30 %, 60 % | PVR, charging conflicts, dead km |
| Local search budget | 0, 5, 10, 30, 60 s | Cost curve (diminishing returns) |

This shows planners and management the **operational cost of policy choices** (e.g. the extra duties required by a longer minimum rest).

### 7.3 Statistical reporting

- Report median and min–max over 5 seeds. Use a paired comparison on the same dataset and seed.
- A claimed improvement must hold for **all** seeds on DS-M, not just on average.

---

## 8. Geospatial evaluation (D4)

### 8.1 Overlap detection accuracy

**Ground truth:** DS-GEO labelled pairs. A pair is **predicted duplicate or overlapping** when the overlap ratio exceeds a threshold.

| Metric | Definition | Target |
|---|---|---|
| Precision (overlap ≥ MEDIUM) | TP ÷ (TP + FP) | ≥ **0.90** |
| Recall (overlap ≥ MEDIUM) | TP ÷ (TP + FN) | ≥ **0.90** |
| F1 | 2PR ÷ (P + R) | ≥ **0.90** |
| Overlap length error | abs(predicted overlap km − labelled km) ÷ labelled km for PARTIAL pairs | Median ≤ **10 %** |
| Direction classification accuracy | Correct `same_direction` on corridor pairs | ≥ **95 %** |
| Junction false positives | Crossing-only pairs reported as overlap | **0** |

**Buffer sensitivity sweep:** run with `bufferM` ∈ {10, 15, 25, 40, 60} and `minSegmentM` ∈ {100, 200, 400}. Plot precision and recall for each setting and justify the chosen defaults (25 m / 200 m), especially on parallel-road cases (EC-GEO-06).

### 8.2 Geometry correctness unit fixtures

| Fixture | Expected |
|---|---|
| Straight line 1 km in Delhi (EPSG:4326 input) | `length_m` = 1,000 ± 1 m |
| Identical patterns | Ratio 1.00 ± 0.01 |
| 3 km shared on a 10 km route | Ratio 0.30 ± 0.01 |
| Parallel line offset 40 m, buffer 25 m | Ratio 0.00 |
| Perpendicular crossing | Ratio 0.00 |
| Out-and-back on the same road | Ratio ≤ 1.00, length not doubled |
| Loop route | Detected as closed, direction `LOOP` |
| Swapped lat/lon input | Rejected with a swapped-coordinates hint |

### 8.3 Coverage accuracy

| Check | Method | Target |
|---|---|---|
| Zone coverage ratio | Compare system output with an independent GIS computation (e.g. QGIS buffer + intersect) on 20 zones | Absolute difference ≤ **2 pp** per zone |
| Grid resolution effect | 100 m vs 250 m cells on the same zones | Difference reported. The 250 m grid must stay within 3 pp of 100 m. |
| Coverage gain of a proposal | Compare with manual computation for 5 proposals | ≤ **3 %** relative error |

### 8.4 Geospatial performance

| Query | Condition | Target (p95) |
|---|---|---|
| Ad-hoc overlap analysis, proposal ≤ 2,000 vertices vs full network (~2,000 patterns on DS-R / DS-L) | GiST on `geom_utm` | < **500 ms** |
| Same query **without** spatial index | Index dropped (experiment only) | Recorded, to show the index speed-up factor |
| Stops in bbox (paginated) | GiST on stop geometry | < **100 ms** |
| Routes passing within 500 m of a point | GiST | < **200 ms** |
| Zone coverage report (all wards) | Materialized grid view | < **2 s** |
| Coverage view refresh after stop edit | Concurrent refresh | < **60 s** on DS-L |

Every query is captured with `EXPLAIN (ANALYZE, BUFFERS)` and the plan is attached to the report.

---

## 9. API and performance evaluation (D3, D5)

### 9.1 Environment

- Application: 1 instance, 4 vCPU and 8 GB RAM for the baseline, then 2 instances for the scaling check.
- Database: PostgreSQL 16 + PostGIS 3.4, 4 vCPU, 16 GB RAM, SSD, loaded with DS-L plus 30 days of published schedules.
- The load generator runs on a separate machine. Warm-up for 2 min, then 10 min of steady state.

### 9.2 Load profile (k6 or Gatling)

| Share | Operation | Example |
|---|---|---|
| 35 % | Filtered paginated lists | `/buses`, `/crew`, `/schedules/{id}/duties?unassigned=true` |
| 15 % | Keyset lists | `/trips`, `/audit-logs` |
| 15 % | Detail reads | `/routes/{id}`, `/schedule-runs/{id}` |
| 10 % | Reports and dashboard | `/reports/fleet-utilization`, `/dashboard/today` |
| 5 % | Spatial queries | `/stops?bbox=`, `/routes?passesNear=` |
| 5 % | Overlap analysis | `POST /routes/overlap-analysis` |
| 10 % | Writes | Leave records, bus status, assignment overrides |
| 5 % | Auth | Login and refresh |

### 9.3 Targets

| Metric | Target |
|---|---|
| Paginated list endpoints, p95 at 50 concurrent users | < **200 ms** |
| Paginated list endpoints, p99 at 50 concurrent users | < **500 ms** |
| Detail endpoints, p95 | < **100 ms** |
| Reports and dashboard, p95 | < **1 s** |
| Overlap analysis, p95 | < **500 ms** |
| Error rate (non-4xx) | < **0.1 %** |
| Throughput at the targets above | ≥ **150 requests/s** on 1 instance |
| Horizontal scaling | 2 instances give ≥ **1.7×** throughput at the same p95 |
| Deep pagination: page 500 at size 100 with offset vs keyset cursor | Keyset p95 stays < 200 ms. Offset degradation recorded. |
| `includeTotal=false` vs count query on `duty_assignment` (30 days) | Speed-up factor recorded |
| DB connection pool saturation | No waits > 50 ms at target load |

### 9.4 Scheduling engine performance

| Scenario | Target |
|---|---|
| Single depot-day, DS-S (~120 buses), linked | < **5 s** |
| Single depot-day, DS-S, unlinked + 30 s search | < **40 s** |
| Largest depot in DS-L, unlinked + 30 s search | < **60 s** |
| Full DS-L (all depots, one service date), 8 vCPU worker | < **15 min** |
| Full DS-L, 7-day horizon | < **2 h** (overnight batch) |
| Memory per depot run | < **512 MB** heap |
| Persist results per depot-day (JDBC batch) | < **5 s** |

Metrics come from Micrometer timers (`scheduling.run.duration`, phase timers) and JFR recordings for hotspots.

---

## 10. Security evaluation (D6)

### 10.1 Authorization matrix testing

- A parameterised integration test iterates **every endpoint × every role × {own depot, other depot, HQ user, unauthenticated}**, using expectations from the [permission matrix](Architecture.md).
- Expected outcomes: 2xx for allowed, 401 unauthenticated, 403 wrong role, 404 out of depot scope.

| Metric | Target |
|---|---|
| Endpoint–role cells covered by automated tests | **100 %** |
| Tests passing | **100 %** |
| Endpoints discovered in OpenAPI but missing from the matrix test | **0** (the test fails if a new endpoint is not classified) |

### 10.2 OWASP API Security Top 10 (2023) checklist

| Risk | Test | Pass criterion |
|---|---|---|
| API1 Broken object-level authorization | Cross-depot ID access on every `{id}` endpoint | 404 for all |
| API2 Broken authentication | Expired, tampered, `alg=none` and HS256-confusion tokens. Refresh reuse. Brute force. | All rejected. Lockout triggers. Token family revoked. |
| API3 Broken object property-level authorization | Mass-assignment payloads (`status`, `version`, `depotId`). PII fields visible to wrong role. | Ignored or rejected. PII masked. |
| API4 Unrestricted resource consumption | `size=100000`, 50k-vertex geometry, 50 MB body, run-creation flood | Clamped, 413/422, 429 |
| API5 Broken function-level authorization | SCHEDULER publish, PLANNER override, MANAGER user admin | 403 |
| API6 Unrestricted access to sensitive business flows | Repeated publish or run creation | Idempotent or 409. Rate limited. |
| API7 SSRF | No endpoint fetches user-supplied URLs | Confirmed by design review |
| API8 Security misconfiguration | Actuator exposure, CORS, headers, stack traces in errors | Only health and info public. Strict CORS. No stack traces. |
| API9 Improper inventory management | Unversioned or undocumented endpoints | All under `/api/v1` and present in OpenAPI |
| API10 Unsafe consumption of APIs | GTFS and CSV imports treated as untrusted | Validated, size-limited, parameterised |

### 10.3 Scans

| Tool | Target |
|---|---|
| Dependency vulnerability scan (OWASP Dependency-Check or equivalent) | 0 critical, 0 high without an accepted exception |
| OWASP ZAP API scan (OpenAPI-driven, authenticated) | 0 high, medium findings triaged |
| Secret scan of the source tree | 0 findings |

---

## 11. Reliability and consistency evaluation (D7)

| Test | Procedure | Pass criterion |
|---|---|---|
| Concurrent run creation | 20 parallel `POST /schedule-runs` for the same depot and date | Exactly 1 run created. Others 409 or idempotent replay. |
| Multi-instance claiming | 2 instances, 50 queued runs | Each run processed exactly once |
| Worker crash | Kill the instance mid-run | Run marked FAILED within 2.5 min. 0 partial schedule rows. Re-run succeeds. |
| Concurrent overrides | 2 clients override overlapping assignments for the same crew | At most one succeeds. No overlap persists after publish (INV-01). |
| Publish race | Publish + revalidation job simultaneously | Serialized. Final state consistent with conflicts. |
| DB guard | Direct SQL insert of an overlapping published assignment | Rejected by exclusion constraint (`23P01`) |
| Idempotent publish | Publish twice with the same idempotency key | Same response, one version |
| Backup and restore | Restore latest backup to a fresh instance | Application starts. Invariants INV-01 to INV-09 return 0 rows. |
| Long soak | 4 h at 50 % target load | No memory growth trend. No connection leaks. Error rate < 0.1 %. |

---

## 12. Test strategy and coverage (D8)

### 12.1 Test pyramid

| Layer | Tools | Scope | Target |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | Engine algorithms, each constraint, value objects, normalisers | Line coverage ≥ **85 %** on `scheduling.engine`, ≥ **80 %** overall domain |
| Property-based | jqwik | Engine invariants (§12.2) | ≥ 1,000 tries per property. **100 %** pass. |
| Mutation | PIT | Constraint classes | Mutation score ≥ **70 %** |
| Integration | Spring Boot Test, Testcontainers `postgis/postgis:16-3.4` | Repositories, native spatial SQL, migrations, publish flow, exclusion constraints | All critical flows covered |
| Architecture | ArchUnit | Module boundaries, engine purity | **0** violations |
| Security | Spring Security Test, matrix tests | Authentication and authorization (§10) | **100 %** matrix |
| Contract / API | OpenAPI validation of responses in integration tests | Response schemas match spec | **100 %** documented endpoints |
| Performance | k6 or Gatling, JMH (engine micro-benchmarks) | §9 | Targets met |

### 12.2 Engine properties (jqwik)

| ID | Property |
|---|---|
| PROP-01 | Every trip is in exactly one block **or** in the uncovered list with a reason |
| PROP-02 | Within a block, each consecutive trip pair satisfies layover + deadhead time |
| PROP-03 | EV blocks never exceed usable range between charging events |
| PROP-04 | Every piece of work belongs to exactly one duty; the union of pieces equals the block work |
| PROP-05 | Every duty emitted without a HARD conflict satisfies all HARD duty constraints |
| PROP-06 | Every handover gap ≥ transfer time + buffer |
| PROP-07 | No crew member is booked on overlapping work periods within a run |
| PROP-08 | Rest between consecutive assignments (including history) ≥ minimum, or the slot is unassigned |
| PROP-09 | Local search output cost ≤ input cost, with no new hard violations |
| PROP-10 | Same input + same seed produce an identical output hash |
| PROP-11 | Tightening any hard limit never produces a schedule that violates the tighter limit (it can only increase conflicts or duties) |

### 12.3 Edge-case coverage

| Metric | Target |
|---|---|
| Edge-case IDs in [Edge case.md](Edge%20case.md) (sections 1–15) with at least one tagged passing test | **100 %** |
| Edge-case IDs in section 16 (operational, out of scope) with documented v1 behaviour | **100 %** |

The CI report lists every `EC-*` ID with its test status. Missing IDs are reported as gaps.

---

## 13. Acceptance criteria per phase

| Phase ([Implementation](Implementation.md)) | Acceptance evidence |
|---|---|
| P0 Foundations | Clean build with Testcontainers. Migrations apply to an empty DB. ArchUnit engine-purity rule passes. |
| P1 Security | Authentication tests (§10.2 API2) pass. Matrix test skeleton in place. Login audited. |
| P2 Master data | Pagination edge cases EC-API-01 to EC-API-10 pass. Cross-depot tests pass. Filtered queries use indexes. |
| P3 Routes & GIS | §8.2 fixtures pass. Overlap p95 < 500 ms. Proposal workflow tests pass. |
| P4 Timetables | Trip counts match analytical counts. DS-S/M/L generated deterministically (checksums). |
| P5 Blocks | PROP-01 to PROP-03 pass. DS-S coverage 100 % (or explained). Greedy PVR gap vs matching reported. |
| P6 Linked duties | PROP-05 passes. DS-S linked run with 0 unexplained hard violations. Constraint boundary tests pass. |
| P7 Unlinked duties | PROP-04, PROP-06, PROP-09 and PROP-10 pass. A3/A5 vs A1 comparison recorded on DS-M. |
| P8 Assignment & publish | PROP-07 and PROP-08 pass. SQL invariants return 0 rows on DS-L. Concurrency tests in §11 pass. |
| P9 Reporting | Report figures equal independent recomputation on DS-S. Audit completeness test passes. |
| P10 Hardening | All §9 targets met or deviations documented. §10.3 scans clean. Restore rehearsal succeeds. |

**Final acceptance (release v1.0):** all phase criteria met, plus §6.3 compliance on DS-L and DS-R, plus the §5 quality targets for unlinked mode vs B2 on DS-M.

---

## 14. Results reporting template

Each evaluation run produces a report with the following sections. **Values are filled in only from measured runs.**

### 14.1 Run metadata

| Field | Value |
|---|---|
| Date | |
| Application version | |
| Dataset(s) and checksum | |
| Rule-set ID and version | |
| Seeds | |
| Hardware (app / DB) | |
| JVM and DB settings | |

### 14.2 Compliance summary

| Dataset | Mode | Hard violations | SQL invariants (rows) | Property tests | Pass? |
|---|---|---|---|---|---|
| DS-S | Linked | | | | |
| DS-S | Unlinked | | | | |
| DS-M | Unlinked | | | | |
| DS-L | Unlinked | | | | |
| DS-R | Unlinked | | | | |

### 14.3 Quality comparison (DS-M, median of 5 seeds)

| Metric | B1 | A1 (B2) | A3 | A5 | Lower bound | Target | Pass? |
|---|---|---|---|---|---|---|---|
| Trip coverage | | | | | – | 100 % | |
| PVR | | | | | | gap ≤ 3 % | |
| Dead-km ratio | | | | | – | ≤ B1 | |
| Duties | | | | | | ≤ A1 − 8 % | |
| Platform-to-paid | | | | | – | ≥ 0.80 | |
| Paid idle hours | | | | | – | ≤ A1 − 15 % | |
| Overtime hours | | | | | – | ≤ B1 | |
| Assignment rate | | | | | – | 100 % / explained | |
| Night-duty Gini | | | | | – | ≤ 0.25 | |

### 14.4 Performance

| Scenario | p50 | p95 | p99 | Throughput | Target | Pass? |
|---|---|---|---|---|---|---|
| List endpoints | | | | | p95 < 200 ms | |
| Overlap analysis | | | | | p95 < 500 ms | |
| Depot-day run (largest) | | | – | – | < 60 s | |
| Full fleet run (DS-L) | – | – | – | – | < 15 min | |

### 14.5 Geospatial accuracy

| Setting (buffer / min segment) | Precision | Recall | F1 | Median length error | Direction accuracy |
|---|---|---|---|---|---|
| 25 m / 200 m (default) | | | | | |
| 15 m / 200 m | | | | | |
| 40 m / 200 m | | | | | |

### 14.6 Baseline comparison (only if DS-HIST is available)

| Metric | Manual (B0) | System | Change |
|---|---|---|---|
| Lead time per depot-day | | | |
| Post-publication conflicts per 1,000 duties | | | |
| Duties per 100 buses | | | |
| Peak vehicle requirement | | | |

### 14.7 Findings and actions

| Finding | Severity | Root cause | Action | Owner |
|---|---|---|---|---|
| | | | | |

---

## 15. Traceability matrix

| Objective ([Project statement](Project%20statement.md)) | Metric(s) | Evidence source | Section |
|---|---|---|---|
| **O1** Automate linked and unlinked duty scheduling for 5,000+ buses via REST APIs, replacing spreadsheets | Trip coverage; DS-L full-fleet run time; lead time vs B0; both modes runnable via `/schedule-runs` | Engine benchmark, run metrics, API integration tests | §5.1, §5.4, §9.4 |
| **O2** Model crew–bus assignments, handovers and rest constraints; reduce conflicts; improve utilization | 0 hard violations; SQL invariants; handover feasibility 100 %; post-publication conflicts vs B0; PVR gap; duties and platform-to-paid vs B1/B2 | Compliance suite, ablation results | §5.2–§5.4, §6, §7 |
| **O3** PostGIS + Hibernate Spatial route management with overlap detection and coverage optimisation | Overlap precision/recall/F1; length error; coverage accuracy; spatial query p95 with vs without index | DS-GEO evaluation, geometry fixtures, EXPLAIN plans | §8 |
| **O4** RBAC with Spring Security; pagination and filtering across 15+ endpoints; real-time data and reporting | Authorization matrix 100 %; endpoint inventory from OpenAPI (48 endpoints, 22 paginated/filterable); list p95; dashboard freshness (`asOf` ≤ 60 s) | Security tests, OpenAPI export, load test report | §9, §10 |
| **O5** Traceable, auditable changes | Audit completeness 100 % of write endpoints; append-only enforcement | Audit completeness test, DB privilege test | §11, §12 |

---

## 16. Threats to validity

| Threat | Effect | Mitigation |
|---|---|---|
| Synthetic data may not reflect real DTC operations (headways, relief-point density, leave patterns) | Quality and performance results may not transfer | Include DS-R with real network geometry and trips. Validate with DTC data before claims about production impact. |
| Labour-rule values are assumptions | Duty counts and assignment rates depend strongly on them | Sensitivity analysis (§7.2). Rule sets reviewed with DTC before production evaluation. |
| B0 manual data may be unavailable or incomplete | Conflict-reduction and lead-time claims cannot be quantified against reality | Don't claim B0 comparisons without data. Report against B1/B2 and state it clearly. |
| Labelled overlap pairs reflect one reviewer's judgement | Precision and recall bias | Two independent labellers on a subset, with agreement (Cohen's κ) reported. Disagreements resolved and documented. |
| Lower bounds are weak (duties) | Gap overstates sub-optimality | Label as upper estimates of the gap. Consider an exact solver on DS-S for a tighter comparison. |
| Performance environment differs from production | Latency targets may not hold | Record the environment. Re-run a smoke benchmark on production-like infrastructure before go-live. |
| Heuristic randomness | Cherry-picked seeds | Fixed seed set, all seeds reported, and improvements must hold across every seed. |
