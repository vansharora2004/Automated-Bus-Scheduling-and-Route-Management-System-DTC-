# Edge Cases — Catalogue, Handling & Tests

> Related: [Project statement](Project%20statement.md) · [Architecture](Architecture.md) · [Implementation](Implementation.md) · [Evaluation](Evaluation.md)

This document lists the edge cases the system must handle, **what the correct behaviour is**, and **how it is verified**. Each case has a stable ID so tests can reference it (e.g. `@Tag("EC-TIME-01")`).

---

## Handling principles

1. **Never silently relax a HARD constraint.** When the engine cannot satisfy a rule, it emits a typed conflict with an explanation.
2. **Validate at the boundary, guard in the database.** Bad input is rejected with a clear 400 or 422. Invariants (no double booking, one active run) are also enforced by DB constraints.
3. **Published means immutable.** Changes create a new version. Anything that invalidates a published schedule raises conflicts and a `needs_revalidation` flag.
4. **Explainability over cleverness.** Every uncovered trip, infeasible duty and unassigned slot carries reasons a scheduler can act on.
5. **Deterministic and reproducible.** Same inputs + rule set + seed produce the same output.

**Test type legend:** `U` unit · `P` property-based (jqwik) · `IT` integration (Testcontainers PostGIS) · `SEC` security test · `CON` concurrency test · `LOAD` performance test · `SQL` invariant query

---

## 1. Time & calendar

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-TIME-01 | Trips run past midnight (last trip departs 00:50 and ends 01:40) | Stored as service-day seconds (`24:50:00` = 89,400 s) and belong to the **previous** service date. `work_period` becomes an absolute `tstzrange` crossing midnight correctly. | U, IT |
| EC-TIME-02 | A late duty ends at 01:30 and the same crew has an early duty at 09:00 the same calendar day (next service date) | Rest is computed on absolute instants (7 h 30 min), so `INSUFFICIENT_REST` is raised when the minimum is 10 h. Not missed because the dates differ. | U, IT |
| EC-TIME-03 | Trip departs before `serviceDayStart` (e.g. 02:30 when the day starts at 03:00) | Treated as part of the previous service day. Import validation warns if this looks unintended. | U |
| EC-TIME-04 | Server JVM default timezone is not IST, or the database session timezone differs | All instants stored as `timestamptz` in UTC. `hibernate.jdbc.time_zone=UTC`. Conversion to IST happens only at API edges. Tests run with `-Duser.timezone=America/New_York` to prove independence. | IT |
| EC-TIME-05 | Holiday or special event (festival, national event) changes day type | `calendar_exception` overrides day type per date or depot. The run picks the override timetable, and the run metadata shows which day type was used. | IT |
| EC-TIME-06 | Timetable validity ends mid-week and a new one starts | Each service date resolves the timetable valid on that date. Date-range runs switch automatically. A gap in validity gives a `NO_TIMETABLE` error for that date. | U, IT |
| EC-TIME-07 | Weekly limit window: rolling 7 days vs calendar week | Rolling 7 days by default (configurable). History is loaded from the previous 6 days, even if they fall in a previous "planning week". | U |
| EC-TIME-08 | Rule set changes mid-week (new max hours effective Thursday) | The rule set applies by the **service date** being scheduled. Earlier days keep their original rule-set ID, stored on the run. | U, IT |
| EC-TIME-09 | Scheduling a date in the past | Rejected with 422 for all roles except ADMIN with `backfill=true` (audited). Published history is never overwritten. | SEC, IT |
| EC-TIME-10 | Scheduling far in the future (beyond timetable or crew data horizon) | Allowed only up to a configurable horizon (e.g. 35 days). Beyond it, 422 with reason. | U |
| EC-TIME-11 | Exactly-at-boundary values (duty work = 480 min when max is 480) | Limits are **inclusive** (`≤`). 480 passes, 480 min + 1 s fails. Documented and tested at the boundary. | U |
| EC-TIME-12 | Clock-dependent code in tests | An injected `Clock` is used everywhere. No `now()` calls in domain code. | U |

## 2. Timetable & trip data

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-TT-01 | Trip with `end_sec ≤ start_sec` | Rejected at import or generation with a row-level error. | U |
| EC-TT-02 | Implausible running time (average speed > 45 km/h or < 5 km/h in the city) | Warning on import. Configurable to block. Flagged in a data-quality report. | U |
| EC-TT-03 | Overlapping headway bands for the same direction | Rejected (422) with the overlapping bands identified. | U |
| EC-TT-04 | Headway larger than band length | Generates at least one trip at the band start. A warning is recorded. | U |
| EC-TT-05 | Duplicate trips (same pattern and departure) from manual edits plus generation | Unique constraint `(timetable_id, pattern_id, start_sec)` with a clear 409. | IT |
| EC-TT-06 | Timetable edited after a schedule is published | Published schedule flagged `needs_revalidation`, with a conflict noting stale trips. No silent change. | IT |
| EC-TT-07 | Route has trips but no running-time band for a time of day | Fall back to the nearest band with a warning, or fail generation, depending on strict mode (default: fail). | U |
| EC-TT-08 | Depot has zero trips on a date (e.g. depot closed) | The run completes with an empty schedule and metrics of 0. Not an error. | IT |
| EC-TT-09 | Trip starts or ends at a non-terminal stop (short-turn trip) | Allowed. No relief opportunity is created unless the stop is flagged as a relief point. | U |
| EC-TT-10 | Missing deadhead pair between two terminals | Estimated as straight-line × detour factor ÷ band speed. `ESTIMATED_DEADHEAD` soft conflict so planners can fill real values. | U |
| EC-TT-11 | Deadhead time differs strongly by time band (peak congestion) | Deadhead lookup uses the departure time band. Boundary-crossing moves use the departure band. | U |

## 3. Vehicle scheduling (blocks)

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-VS-01 | Trip requires a vehicle class the depot does not have (low-floor, AC, electric) | `UNCOVERED_TRIP` with reason `NO_VEHICLE_OF_CLASS`. **Never** assigned to a lower class. A higher-class substitution is allowed only if the rule set enables it. | U, P |
| EC-VS-02 | Fleet shortage (trips need more buses than available at peak) | Trips are covered in priority order (route priority, then ridership rank if present). The rest are `UNCOVERED_TRIP` with `FLEET_SHORTAGE`. The report shows peak shortfall per time bucket. | U |
| EC-VS-03 | Timetabled layover shorter than the minimum at a terminal | The block cannot chain those trips, so another bus is used. If this repeats, a soft warning suggests timetable adjustment. | U |
| EC-VS-04 | Long midday gap (> 90 min) at a terminal far from the depot | Compare the depot-return cost (deadhead km and time) with terminal waiting (parking capacity). Choose the cheaper legal option and record the choice. | U |
| EC-VS-05 | Electric bus block exceeds usable range (range × (1 − reserve)) | Block split with a `CHARGING` event at the depot if the gap allows. Otherwise the trip goes to another block. If no feasible EV is left, `EV_RANGE_EXCEEDED` or `UNCOVERED_TRIP`. | U, P |
| EC-VS-06 | Depot charging bays limited (more EVs need charging at once than bays) | Charging events are scheduled against bay capacity with a sweep line. Overflow gives an `EV_CHARGING_CAPACITY` conflict. | U |
| EC-VS-07 | Bus in maintenance for part of the day (10:00–14:00) | That bus can only take blocks that do not overlap the unavailability `tstzrange`. | IT |
| EC-VS-08 | Bus breaks down after the schedule is published | Status change creates `BUS_UNAVAILABLE` on the affected block. Suggested replacement: a spare bus of the same class. A new schedule version is required. The published version is never edited. | IT |
| EC-VS-09 | Night parking exceeds depot capacity (more buses than the depot can hold) | Capacity checked on pull-in counts. A soft conflict tells the manager. | U |
| EC-VS-10 | Two trips share an identical start time and stop | Deterministic tiebreak by trip ID ensures stable output. | P |
| EC-VS-11 | Trip whose origin is another depot's terminal (inter-depot route) | Covered by the operating depot defined on the route. Deadhead from the owning depot is used. | U |
| EC-VS-12 | Extremely long block (bus used 20 h) | Allowed only up to `maxBlockDurationMin` (maintenance or cleaning needs). Above it the block is split. | U |

## 4. Linked duties

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-LD-01 | Peak-only block (07:00–10:30) much shorter than a normal duty | A short duty is produced, with a `SHORT_DUTY` soft conflict (below the paid guarantee). Unlinked mode is suggested in the run report. | U |
| EC-LD-02 | Block has a stretch longer than `maxContinuousWork` between relief opportunities | `NO_FEASIBLE_RELIEF` hard conflict with the time window. Recommends adding a relief point or adjusting the timetable. The duty is still shown so the gap is visible. | U |
| EC-LD-03 | A break is owed but every layover on the bus is shorter than 30 min | Cut the duty earlier at a relief point so a new crew takes over (the break is not needed). If cutting is impossible, `CONTINUOUS_WORK_EXCEEDED`. | U |
| EC-LD-04 | Relief point is a terminal far from the depot | The incoming crew's travel time from the depot (sign-on) is counted as paid time, and so is the outgoing crew's return. Both are included in spread-over. | U |
| EC-LD-05 | Split linked duty: the bus parks at the depot midday and the same crew returns | Allowed when spread-over ≤ max. Duty type `SPLIT`. Unpaid gap as per rule set. | U |
| EC-LD-06 | Constraint not monotone: an early cut fails but a later cut is feasible due to a long layover | The builder checks all candidate cut points, not only until the first failure. | U, P |
| EC-LD-07 | Tiny tail duty (e.g. 40 min at the end of a block) | The cut is chosen with a penalty for a remainder < `minPaidDuty`, so the earlier cut shifts to balance duties. | U |
| EC-LD-08 | A bus requires a driver and a conductor, but some routes or buses operate without a conductor | Crew composition comes from the bus or route configuration. Slots are created per required role only. | U |

## 5. Unlinked duties & handovers

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-UD-01 | Outgoing piece ends at Terminal A and the next piece starts at Terminal B | Gap must be ≥ transfer time (A→B) + handover buffer. Otherwise the combination is not allowed. `HANDOVER_INFEASIBLE` if forced manually. | U, P |
| EC-UD-02 | Handover gap exactly 0 (arrive 10:00, next bus departs 10:00) | Rejected. Minimum `handoverBufferMin` applies even at the same stop. | U |
| EC-UD-03 | Crew would change buses 4 times in a duty | Limited by `maxBusChangeoversPerDuty` (soft by default, can be made hard). Cost penalty discourages it. | U |
| EC-UD-04 | Duty ends at a relief point away from the depot | Sign-off travel back to the depot is added to paid time and spread-over. | U |
| EC-UD-05 | The gap between pieces is long enough to count as a break only if taken at a location with facilities | Relief points carry `hasCrewFacilities`. A break counts only where it is true (configurable). | U |
| EC-UD-06 | A piece is assigned to two duties (bug) or to none | Invariant: every piece in exactly one duty (DB unique on `duty_piece.piece_id` plus a property test). Orphan pieces become `UNCOVERED_PIECE`. | P, IT |
| EC-UD-07 | Local search move creates a hard violation | The move is rejected before applying. A property test asserts no hard violation after improvement. | P |
| EC-UD-08 | Conductor handover involves ticket machine and cash reconciliation | Extra `conductorHandoverBufferMin` applies for the conductor role. | U |
| EC-UD-09 | Different seeds give very different schedules, which confuses users | Seed stored on the run. The default seed is fixed per depot and date, so re-runs are stable unless the user asks for a different seed. | U |
| EC-UD-10 | Local search time budget expires immediately (overloaded CPU) | The greedy solution (already legal) is returned. Metrics show `improvementIterations=0`. | U |

## 6. Rest & labour rules

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-REST-01 | Two short breaks (15 + 15 min) instead of one 30-min break | Does **not** reset continuous work by default (rules require a single interval). A configurable flag allows cumulative breaks if policy permits. | U |
| EC-REST-02 | Break of 29 min 59 s | Not a qualifying break (inclusive ≥ 30 min). | U |
| EC-REST-03 | Spread-over of a split duty exceeds 12 h while work time is only 7 h | `SPREAD_OVER_EXCEEDED` (hard), independent of work time. | U |
| EC-REST-04 | Weekly hours: crew worked overtime yesterday (actual > planned) | Weekly calculation uses **actual** hours where recorded, otherwise planned. | IT |
| EC-REST-05 | No weekly rest day in the rolling 7 days if assigned today | `WEEKLY_REST_MISSING`, so the crew is not eligible today. | U |
| EC-REST-06 | Crew's weekly off falls on a holiday or a special operations day | Eligibility respects the weekly off unless the crew volunteers (explicit availability record). Audited. | U |
| EC-REST-07 | Previous-day history missing (first week of system use) | The loader flags `HISTORY_INCOMPLETE`. A conservative assumption applies (the crew is treated as having worked the maximum) or an import of the last 7 days of manual rosters is required, depending on configuration. | IT |
| EC-REST-08 | Overtime enabled with a cap | Duty work may exceed the standard up to `maxOvertimeMin`. Anything above is hard. Overtime is counted in reports. | U |

## 7. Crew assignment (rostering)

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-CA-01 | Licence expires on the service date or earlier | Ineligible (`LICENCE_INVALID`). Licences expiring within 30 days surface in `/crew?licenceExpiringBefore=`. | U, IT |
| EC-CA-02 | Licence class does not match the bus type (e.g. heavy passenger vehicle required) | Ineligible. The class requirement is derived from the block's vehicle class. | U |
| EC-CA-03 | Half-day leave (09:00–13:00) | Leave stored as `tstzrange`. Only duties overlapping the leave are excluded. | U |
| EC-CA-04 | Duty on an electric bus and crew lacks EV training | `QUALIFICATION_MISSING`. | U |
| EC-CA-05 | Crew transferred to another depot mid-week | `crew_depot_history` with effective dates. Weekly hours carry over across depots. | IT |
| EC-CA-06 | More duties than eligible crew | Hardest slots (fewest candidates) are assigned first. The rest are `UNASSIGNED_DUTY` with a reason histogram, e.g. "9 INSUFFICIENT_REST, 6 ON_LEAVE". | U |
| EC-CA-07 | More crew than duties | Extra crew go into the standby pool up to `standbyPoolPct`, then off-duty. Fairness decides who goes to standby. | U |
| EC-CA-08 | The same crew always gets night duties (starvation) | Fairness score penalises a recent night-duty count. The report shows the distribution. `FAIRNESS_IMBALANCE` above the threshold. | U |
| EC-CA-09 | Crew suspended or terminated after assignment | Status change triggers revalidation of future assignments, giving a conflict on published schedules. | IT |
| EC-CA-10 | Crew absent on the day (no-show) | Operational: the scheduler assigns from the standby pool through an override (eligibility re-checked). The absence is recorded for history. | IT |
| EC-CA-11 | Driver–conductor pairing preference conflicts with legality | Pairing is soft only and never overrides hard eligibility. | U |
| EC-CA-12 | Manual override assigns crew with a HARD violation | Rejected (422) with violations listed, **for all roles**. | IT, SEC |
| EC-CA-13 | Manual override violates a SOFT rule | Allowed for MANAGER or ADMIN with a mandatory `reason`. Rejected (403) for SCHEDULER. Audited. | SEC |
| EC-CA-14 | Cross-depot loan of crew for one day | Explicit `crew_loan` record. The exclusion constraint still prevents overlaps across depots, because it is keyed on crew, not depot. | IT |
| EC-CA-15 | Tie in fairness score between candidates | Deterministic tiebreak by employee code. | P |

## 8. Schedule lifecycle, overrides & publishing

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-LC-01 | Publish with open HARD conflicts | 422 `PUBLISH_BLOCKED`, listing conflict counts by type. | IT |
| EC-LC-02 | Publish called twice (double click, retry after timeout) | Idempotent: the second call returns the same result, with no new version and no error. | CON, IT |
| EC-LC-03 | Publish new version while the old one is published | Single transaction: old → SUPERSEDED, new → PUBLISHED. The partial unique index guarantees exactly one published schedule. | IT |
| EC-LC-04 | New version's assignment overlaps the previous service date's published late duty | Exclusion constraint (`23P01`) aborts the publish, mapped to 409 with the crew and time range. | IT, SQL |
| EC-LC-05 | Edit after VALIDATED | State returns to DRAFT. Validation is required again before publish. | U |
| EC-LC-06 | Attempt to modify a PUBLISHED schedule directly | 409 `SCHEDULE_IMMUTABLE`. The client must create a new version (copy of published). | IT |
| EC-LC-07 | Master data changes affecting multiple future published schedules (licence expiry affects 14 days) | A single revalidation job batches the affected dates with a debounce, avoiding a revalidation storm. | IT |
| EC-LC-08 | Discarding a draft that has manual overrides | Allowed with confirmation. Overrides are kept in the audit log. | IT |

## 9. Geospatial & route management

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-GEO-01 | GeoJSON coordinates given as `[lat, lon]` instead of `[lon, lat]` | Geometry falls outside the service area. Rejected with the hint "coordinates appear swapped (expected [lon, lat])". | U, IT |
| EC-GEO-02 | Geometry in EPSG:3857 (web mercator metres) or with a CRS member | Values outside ±180/±90 are rejected. Only EPSG:4326 is accepted. The message explains the expected CRS. | U |
| EC-GEO-03 | LineString with fewer than 2 distinct points or with consecutive duplicates | Duplicates removed. If fewer than 2 distinct points remain, 422. | U |
| EC-GEO-04 | Invalid or self-intersecting polygon for a zone or service area | `ST_IsValid` check. Rejected with `ST_IsValidReason`. Optional `makeValid=true` repair, reported to the user. | IT |
| EC-GEO-05 | Length or buffer computed in degrees by mistake | All metric operations use `geom_utm` (EPSG:32643). A unit test on a known 1 km line asserts length ≈ 1,000 m. | IT |
| EC-GEO-06 | Parallel roads within the buffer (service lane beside a main road, flyover above a road) | Default buffer 25 m reduces false positives. Shared-stop and direction signals are reported alongside. Planners can re-run with a smaller buffer. Evaluated via buffer sensitivity (see [Evaluation §8](Evaluation.md)). | IT |
| EC-GEO-07 | Routes cross at a junction (short intersection) | Segments < `minSegmentM` (200 m) are ignored, so a crossing is not reported as overlap. | IT |
| EC-GEO-08 | Same corridor, opposite direction (UP of the proposal vs DOWN of the existing route) | Reported with `same_direction = false`. Severity computed per direction. Counted separately in summary. | IT |
| EC-GEO-09 | Loop or ring route (start = end, e.g. ring-road services) | `ST_IsClosed` detected and direction `LOOP`. Direction comparison uses segment orientation, not start/end positions. | IT |
| EC-GEO-10 | Route goes out and back on the same road within one pattern | `ST_LineMerge(ST_UnaryUnion(g))` before length, so overlap is not double counted and the ratio stays ≤ 1. | IT |
| EC-GEO-11 | Noisy GPS-traced polyline (zig-zags, spikes) | `ST_SimplifyPreserveTopology` (≈ 5 m) plus spike detection (a vertex creating an implausible detour). Warn and simplify. | IT |
| EC-GEO-12 | MultiLineString with gaps (map-matching failures) | `ST_LineMerge`. If gaps > 50 m remain, 422 with gap locations. | IT |
| EC-GEO-13 | Stop far from the route line (> 50 m) | Warning in the response. `dist_from_start_m` still computed via `ST_LineLocatePoint`. Blocking in strict mode. | IT |
| EC-GEO-14 | Stop sequence order does not match the position along the line | Validation: `dist_from_start_m` must be non-decreasing by `seq` (loop routes allow wrap once). Otherwise 422. | U |
| EC-GEO-15 | Huge geometry (50,000 vertices) or huge request body | Vertex cap (e.g. 5,000 after simplification) and body size limit give 413 or 422. Protects DB CPU. | U, SEC |
| EC-GEO-16 | Proposed route entirely outside the service area | 422 `OUTSIDE_SERVICE_AREA`. | IT |
| EC-GEO-17 | Comparing geometries with `equals` in Java | Never used for business logic. Use `ST_Equals` or tolerance-based comparison. | U |
| EC-GEO-18 | Overlap query when the proposal is already stored as a pattern | Its own pattern ID and other patterns of the same route are excluded from candidates. | IT |
| EC-GEO-19 | Coverage zone without population data | Area-based ratio used. The response indicates `weighting = AREA`. | IT |
| EC-GEO-20 | Stops edited while the coverage materialized view refreshes | `REFRESH MATERIALIZED VIEW CONCURRENTLY` (needs a unique index), debounced. Readers keep the previous snapshot. | IT |
| EC-GEO-21 | Route numbers reused (a retired route number assigned to a new route) | Partial unique index on `route_no` among non-retired routes. History is preserved. | IT |
| EC-GEO-22 | Proposal edited after analysis was attached | Analysis marked stale (pattern `version` differs). Must re-run before submit or decision. | IT |

## 10. Data import & data quality

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-DATA-01 | Registration numbers in different formats (`DL1PC1234`, `DL 1PC 1234`, `dl-1pc-1234`) | Normalised to `DL1PC1234` before the uniqueness check. The original is kept for display. | U |
| EC-DATA-02 | Employee codes lost leading zeros in Excel (`00123` → `123`) | Codes stored as text. Import warns when the code length doesn't match the configured pattern. A mapping option is offered. | U |
| EC-DATA-03 | Dates in `dd/MM/yyyy` vs `MM/dd/yyyy` | Explicit format parameter on import. Ambiguous values (e.g. 03/04) are rejected when the format is not specified. | U |
| EC-DATA-04 | Hindi names and stop names, non-UTF-8 CSV (e.g. Windows-1252 export) | UTF-8 required. BOM handled. Invalid byte sequences give a row error with the line number. | U |
| EC-DATA-05 | Partial file errors (3 bad rows out of 5,000) | Default all-or-nothing per file with a full error report. `dryRun=true` validates without writing. | IT |
| EC-DATA-06 | Unknown foreign key (crew row references depot code `DPT-99`) | Row error listing the unknown code. Nothing is created implicitly. | IT |
| EC-DATA-07 | Duplicate rows inside the same file | Detected in the file first, reported with both line numbers. | U |
| EC-DATA-08 | Very large import (100k rows) | Streaming parse, JDBC batch writes, memory bounded. Progress reported. | LOAD |

## 11. API, pagination & filtering

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-API-01 | `size=100000` | Clamped to 100. The response shows the effective size. | IT |
| EC-API-02 | `page=-1` or `size=0` | 400 with a field error. | IT |
| EC-API-03 | Page beyond the last page | 200 with empty `content` and correct `totalElements`. | IT |
| EC-API-04 | `sort=passwordHash,asc` or an unknown or non-indexed field | 400 listing allowed fields (whitelist). Prevents data probing and slow scans. | SEC, IT |
| EC-API-05 | Records inserted between page requests (offset drift, duplicates or skips) | Stable sort with `id` tiebreaker. High-churn collections use keyset cursors. | IT |
| EC-API-06 | Count query too slow on huge tables | `includeTotal=false` returns a Slice with `hasNext`. Keyset endpoints never count. | LOAD |
| EC-API-07 | Inverted range filter (`from=2026-09-20&to=2026-09-10`) | 400 `INVALID_RANGE`. | U |
| EC-API-08 | Unknown enum value (`status=ACTVE`) | 400 with allowed values. | IT |
| EC-API-09 | `q` search containing `%` or `_` | Escaped before `LIKE`. Always a bound parameter. | SEC |
| EC-API-10 | Malformed bbox (`minLon > maxLon`, out of range, not 4 numbers) | 400 with explanation. | U |
| EC-API-11 | Client retries `POST /schedule-runs` after a network timeout | `Idempotency-Key` returns the original run (no duplicate). Without a key, the partial unique index still prevents a second active run (409). | CON |
| EC-API-12 | `PATCH` without `If-Match` on a versioned resource | 428 Precondition Required. A stale `If-Match` gives 412. | IT |
| EC-API-13 | Large report export | Streamed CSV (no full materialisation in memory). Timeouts configured. | LOAD |
| EC-API-14 | Invalid cursor (tampered or from another endpoint) | 400 `INVALID_CURSOR`. Cursors are opaque and validated (HMAC-signed). | SEC |
| EC-API-15 | SSE client disconnects mid-run | Emitter cleaned up. The run continues unaffected. The client can reconnect and get the latest status. | IT |
| EC-API-16 | Mass assignment: client sends `status=PUBLISHED`, `version`, `depotId` or `id` in a create body | Ignored or rejected. Request DTOs don't contain these fields. Unknown JSON properties give 400 (`FAIL_ON_UNKNOWN_PROPERTIES`). | SEC |

## 12. Security & access control

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-SEC-01 | Scheduler of Depot A requests `/buses/{id}` of Depot B (IDOR/BOLA) | 404, not 403, so existence isn't revealed. | SEC |
| EC-SEC-02 | Scheduler of Depot A lists `/crew?depotId=B` | Depot scope overrides the filter, giving an empty page (or 404 for explicit other-depot filter, consistently documented). | SEC |
| EC-SEC-03 | User disabled or roles reduced while holding a valid access token | `token_version` check rejects the token within the cache TTL (≤ 60 s), always before 15-minute expiry. | SEC |
| EC-SEC-04 | Stolen refresh token reused after rotation | Reuse detection revokes the whole token family. The user must log in again. Security event audited. | SEC |
| EC-SEC-05 | Brute-force login | Rate limit per IP and username. Lockout after 5 failures for 15 min. Generic error message (no user enumeration). | SEC |
| EC-SEC-06 | JWT with `alg=none`, HS256 signed with the public key, expired, wrong issuer or audience | Rejected (only RS256 with configured keys, `iss`, `aud` and `exp` validated). | SEC |
| EC-SEC-07 | SCHEDULER calls `POST /schedules/{id}/publish` | 403. | SEC |
| EC-SEC-08 | User has multiple roles (PLANNER + SCHEDULER) | Permissions are a union. Depot scope still applies to depot-scoped operations. | SEC |
| EC-SEC-09 | SCHEDULER account with no depot assigned (misconfiguration) | Denied for depot-scoped operations (fail closed). ADMIN warned in user listing. | SEC |
| EC-SEC-10 | Crew PII (phone, address, licence) visible to planners | Response DTO masks fields based on role. Logs never contain PII. | SEC |
| EC-SEC-11 | Attempt to alter or delete audit records via the application DB user | UPDATE and DELETE privileges revoked on `audit_log`. The operation fails. | IT |
| EC-SEC-12 | Actuator endpoints (`env`, `heapdump`) exposed publicly | Only `health` and `info` public. Others on a management port or internal network. | SEC |
| EC-SEC-13 | CORS from an unknown origin | Rejected by the allow-list. No wildcard with credentials. | SEC |
| EC-SEC-14 | SQL injection through native spatial query parameters (GeoJSON string) | Always bound parameters. GeoJSON parsed and validated in Java before reaching SQL. | SEC |
| EC-SEC-15 | A planner approves their own route proposal while also holding MANAGER role | Configurable separation-of-duties rule: the approver must differ from the submitter. | SEC |

## 13. Concurrency & consistency

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-CON-01 | Two users start runs for the same depot and date simultaneously | Partial unique index: exactly one row inserted. The other gets 409 with the existing run ID. | CON |
| EC-CON-02 | Two app instances try to claim the same queued run | `FOR UPDATE SKIP LOCKED` ensures exactly one claims it. | CON |
| EC-CON-03 | App instance crashes mid-run | Heartbeat stops. The reaper marks the run FAILED (`WORKER_LOST`) after 2 min. Results were written in a single transaction, so no partial rows remain. The user can re-run. | IT |
| EC-CON-04 | Two schedulers override assignments for the same crew in different duties at the same time, each individually legal but overlapping together | Draft: override re-validates within a transaction holding a crew-level advisory lock (`pg_advisory_xact_lock(crew_id)`). On publish, the exclusion constraint is the final guard. | CON |
| EC-CON-05 | Optimistic lock conflict on a bus or crew edit | 409 with the current version. The client reloads. | IT |
| EC-CON-06 | Master data changes while a run is loading its snapshot | Snapshot loaded in one `REPEATABLE READ` transaction, so consistent. Changes after snapshot trigger revalidation of the resulting draft. | IT |
| EC-CON-07 | Publish races with a revalidation job flagging new conflicts | Publish locks the schedule row (`FOR UPDATE`). Revalidation takes the same lock, so they run in order, never interleaved. | CON |
| EC-CON-08 | Deadlocks during large batch inserts | Inserts ordered by table and key (`order_inserts`). Retry once on `40P01` with jitter. | LOAD |

## 14. Performance & scale

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-PERF-01 | Full fleet (5,000+ buses, ~50k trips) scheduled at once | Partitioned by depot-day. Bounded parallel workers. Memory per run bounded (snapshot for one depot only). | LOAD |
| EC-PERF-02 | One very large depot (≫ average size) | Local search has a time budget. Greedy is O(T·B). Monitored via `scheduling.run.duration`. | LOAD |
| EC-PERF-03 | N+1 queries in list endpoints (bus → depot) | DTO projections or entity graphs. A Hibernate statistics assertion in tests caps query count per request. | IT |
| EC-PERF-04 | Spatial query not using the index (`ST_Transform` in `WHERE`) | Queries use the precomputed `geom_utm`. `EXPLAIN` checks in tests or benchmarks. | LOAD |
| EC-PERF-05 | `IDENTITY` IDs silently disabling JDBC batching | Sequence IDs with pooled allocation. A test asserts batch statements are used. | IT |
| EC-PERF-06 | Report queries scanning months of data | Materialized views plus monthly partitions and date filters required. | LOAD |
| EC-PERF-07 | Coverage over the whole city with many stops | Grid-cell precomputation instead of a runtime union of thousands of buffers. | LOAD |

## 15. Reporting

| ID | Scenario | Expected handling | Test |
|---|---|---|---|
| EC-REP-01 | Report requested for a date with only a DRAFT schedule | Excluded by default. `includeDraft=true` (MANAGER) returns it clearly labelled. | IT |
| EC-REP-02 | Superseded versions double counting | Reports only aggregate PUBLISHED schedules. | IT |
| EC-REP-03 | Service-day vs calendar-day grouping (duty after midnight) | Group by `service_date`. The documented field name makes this explicit. | U |
| EC-REP-04 | Division by zero (depot with no blocks) | `NULLIF` in SQL. Ratio returned as `null`, not an error or `NaN`. | IT |
| EC-REP-05 | Materialized view stale right after publish | Debounced concurrent refresh on the publish event. The response includes `asOf` timestamp. | IT |

## 16. Operational disruptions (mostly out of v1 scope; minimum support defined)

| ID | Scenario | v1 behaviour |
|---|---|---|
| EC-OPS-01 | Temporary road closure or diversion (events, VIP movement, waterlogging) | Planner creates a temporary pattern variant with validity dates. The affected date is re-scheduled as a new version. |
| EC-OPS-02 | Strike or mass absenteeism | Unassigned-duty conflicts plus a fleet or crew shortage report. Managers decide trip cancellations manually. |
| EC-OPS-03 | Real-time delays causing a missed handover | Out of scope (no AVL). The scheduler records a manual override. Future: live-ops module. |
| EC-OPS-04 | Sudden demand spike (special event) needing extra trips | Planner adds trips to a date-specific timetable exception, then re-runs the schedule for that date. |

---

## Traceability

- Every test that verifies an edge case is tagged with its ID, e.g. `@Tag("EC-GEO-01")`.
- The CI report lists edge-case IDs with pass or fail. Uncovered IDs are shown as gaps in [Evaluation §12](Evaluation.md).
