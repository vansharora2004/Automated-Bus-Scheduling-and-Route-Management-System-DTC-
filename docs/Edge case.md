# Automated Bus Scheduling and Route Management System — Edge Cases

This document lists the edge cases the system must handle, what the correct behaviour is, and how it is verified.

Each case has a stable ID so that tests can reference it directly:

```text
@Tag("EC-GEO-01")
```

## Handling principles

A hard constraint is never silently relaxed. When the engine cannot satisfy a rule, it emits a typed conflict with an explanation instead of quietly bending the rule.

Input is validated at the boundary and guarded in the database. Bad input is rejected with a clear 400 or 422, and invariants such as "no double booking" are also enforced by database constraints.

Published means immutable. Changes create a new version. Anything that invalidates a published schedule raises conflicts and sets a revalidation flag.

Explainability matters more than cleverness. Every uncovered trip, infeasible duty and unassigned slot carries reasons a scheduler can act on.

Runs are deterministic. The same inputs, rule set and seed produce the same output.

## Test type legend

```text
U      unit test
P      property-based test (jqwik)
IT     integration test (Testcontainers PostGIS)
SEC    security test
CON    concurrency test
LOAD   performance test
SQL    invariant query
```

---

# 1. Time and Calendar

## EC-TIME-01 Trips that run past midnight

A last trip departs at 00:50 and ends at 01:40.

It is stored as service-day seconds and belongs to the previous service date:

```text
24:50:00 = 89,400 s
```

The `work_period` becomes an absolute `tstzrange` that crosses midnight correctly.

Tests: U, IT

## EC-TIME-02 Rest across a date boundary

A late duty ends at 01:30 and the same crew has an early duty at 09:00 on the same calendar day, which is the next service date.

Rest is computed on absolute instants, so the gap is 7 h 30 min and `INSUFFICIENT_REST` is raised when the minimum is 10 h. It is not missed simply because the service dates differ.

Tests: U, IT

## EC-TIME-03 Trip before the service-day start

A trip departs at 02:30 when the service day starts at 03:00.

It is treated as part of the previous service day. Import validation warns in case this was unintended.

Tests: U

## EC-TIME-04 Server timezone is not IST

The JVM default timezone is not IST, or the database session timezone differs.

All instants are stored as `timestamptz` in UTC, with `hibernate.jdbc.time_zone=UTC`. Conversion to IST happens only at the API edges.

Tests run with an explicit foreign timezone to prove independence:

```text
-Duser.timezone=America/New_York
```

Tests: IT

## EC-TIME-05 Holiday or special event changes the day type

A `calendar_exception` overrides the day type for a date, optionally per depot. The run picks the override timetable, and the run metadata records which day type was used.

Tests: IT

## EC-TIME-06 Timetable validity ends mid-week

Each service date resolves the timetable valid on that date, so a date-range run switches automatically. A gap in validity produces a `NO_TIMETABLE` error for that date rather than silently using the wrong timetable.

Tests: U, IT

## EC-TIME-07 Rolling 7 days against calendar week

The weekly limit window is a rolling 7 days by default, and this is configurable.

History is loaded from the previous 6 days even when those days fall in a previous planning week.

Tests: U

## EC-TIME-08 Rule set changes mid-week

A new maximum-hours rule takes effect on Thursday.

The rule set applies by the service date being scheduled. Earlier days keep their original rule-set id, which is stored on the run.

Tests: U, IT

## EC-TIME-09 Scheduling a date in the past

Rejected with 422 for all roles, except ADMIN with `backfill=true`, which is audited. Published history is never overwritten.

Tests: SEC, IT

## EC-TIME-10 Scheduling far in the future

Allowed only up to a configurable horizon, for example 35 days. Beyond that the request is rejected with 422 and a reason, because timetable and crew data do not extend that far.

Tests: U

## EC-TIME-11 Values exactly at a boundary

Duty work is 480 minutes when the maximum is 480.

Limits are inclusive. 480 passes, 480 minutes plus one second fails. This is documented and tested at the boundary.

Tests: U

## EC-TIME-12 Clock-dependent code in tests

An injected `Clock` is used everywhere. There are no `now()` calls in domain code.

Tests: U

---

# 2. Timetable and Trip Data

## EC-TT-01 Trip ends before it starts

A trip where `end_sec <= start_sec` is rejected at import or generation with a row-level error.

Tests: U

## EC-TT-02 Implausible running time

Average speed above 45 km/h or below 5 km/h in the city.

A warning is raised on import, configurable to block instead, and the trip is flagged in a data-quality report.

Tests: U

## EC-TT-03 Overlapping headway bands

Two bands for the same direction overlap. Rejected with 422, identifying the overlapping bands.

Tests: U

## EC-TT-04 Headway larger than the band length

At least one trip is generated at the band start, and a warning is recorded.

Tests: U

## EC-TT-05 Duplicate trips

The same pattern and departure time exist twice, usually from manual edits plus generation.

```text
UNIQUE (timetable_id, pattern_id, start_sec)
```

The violation surfaces as a clear 409.

Tests: IT

## EC-TT-06 Timetable edited after publication

The published schedule is flagged `needs_revalidation`, with a conflict noting the stale trips. Nothing changes silently.

Tests: IT

## EC-TT-07 Missing running-time band for a time of day

Either fall back to the nearest band with a warning, or fail generation, depending on strict mode. The default is to fail.

Tests: U

## EC-TT-08 Depot has zero trips on a date

For example, the depot is closed.

The run completes with an empty schedule and metrics of zero. This is not an error.

Tests: IT

## EC-TT-09 Short-turn trip

A trip starts or ends at a non-terminal stop. This is allowed, and no relief opportunity is created there unless the stop is flagged as a relief point.

Tests: U

## EC-TT-10 Missing deadhead pair

No measured deadhead exists between two terminals.

It is estimated as straight-line distance times a detour factor, divided by band speed. An `ESTIMATED_DEADHEAD` soft conflict is raised so planners know to fill in real values.

Tests: U

## EC-TT-11 Deadhead varies strongly by time of day

Peak congestion makes a terminal-to-terminal move much slower.

The deadhead lookup uses the departure time band. A move crossing a band boundary uses the departure band.

Tests: U

---

# 3. Vehicle Scheduling

## EC-VS-01 Required vehicle class not available

A trip needs a low-floor, AC or electric bus and the depot has none.

```text
UNCOVERED_TRIP
reason: NO_VEHICLE_OF_CLASS
```

The trip is never assigned to a lower class. A higher-class substitution is allowed only when the rule set enables it.

Tests: U, P

## EC-VS-02 Fleet shortage at peak

Trips need more buses than exist.

Trips are covered in priority order, using route priority and then ridership rank where present. The rest become `UNCOVERED_TRIP` with reason `FLEET_SHORTAGE`, and the report shows peak shortfall per time bucket.

Tests: U

## EC-VS-03 Layover shorter than the minimum

The block cannot chain those trips, so another bus is used. When this repeats on the same route, a soft warning suggests adjusting the timetable.

Tests: U

## EC-VS-04 Long mid-day gap far from the depot

The gap exceeds 90 minutes at a terminal far from the depot.

The builder compares the depot-return cost, meaning deadhead kilometres and time, against waiting at the terminal, which consumes parking capacity. It chooses the cheaper legal option and records the choice.

Tests: U

## EC-VS-05 Electric bus block exceeds usable range

Usable range is the rated range multiplied by one minus the reserve percentage.

The block is split with a `CHARGING` event at the depot if the gap allows. Otherwise the trip goes to another block. If no feasible electric bus remains, the result is `EV_RANGE_EXCEEDED` or `UNCOVERED_TRIP`.

Tests: U, P

## EC-VS-06 Not enough charging bays

More electric buses need charging at once than the depot has bays.

Charging events are scheduled against bay capacity using a sweep line. Overflow raises an `EV_CHARGING_CAPACITY` conflict.

Tests: U

## EC-VS-07 Bus in maintenance for part of the day

A bus is unavailable from 10:00 to 14:00.

It can only take blocks that do not overlap the unavailability range.

Tests: IT

## EC-VS-08 Bus breaks down after publication

The status change creates `BUS_UNAVAILABLE` on the affected block, and a spare bus of the same class is suggested as a replacement.

A new schedule version is required. The published version is never edited in place.

Tests: IT

## EC-VS-09 Night parking exceeds depot capacity

Capacity is checked against pull-in counts, and a soft conflict informs the manager.

Tests: U

## EC-VS-10 Two trips with identical start time and stop

A deterministic tiebreak by trip id keeps the output stable across runs.

Tests: P

## EC-VS-11 Inter-depot route

A trip's origin is another depot's terminal.

It is covered by the operating depot defined on the route, and deadhead from the owning depot is used.

Tests: U

## EC-VS-12 Extremely long block

A bus would be used for 20 hours.

This is allowed only up to `maxBlockDurationMin`, because buses need cleaning and maintenance time. Above that the block is split.

Tests: U

---

# 4. Linked Duties

## EC-LD-01 Peak-only block

A block runs 07:00 to 10:30, much shorter than a normal duty.

A short duty is produced with a `SHORT_DUTY` soft conflict, because it falls below the paid guarantee. The run report suggests unlinked mode for this depot.

Tests: U

## EC-LD-02 No relief opportunity within the work limit

A stretch between relief opportunities is longer than `maxContinuousWork`.

`NO_FEASIBLE_RELIEF` is raised as a hard conflict with the time window, recommending a new relief point or a timetable adjustment. The duty is still shown, so the gap is visible rather than hidden.

Tests: U

## EC-LD-03 A break is owed but no layover is long enough

Cut the duty earlier at a relief point so a new crew takes over, which removes the need for the break. If cutting is impossible, raise `CONTINUOUS_WORK_EXCEEDED`.

Tests: U

## EC-LD-04 Relief point far from the depot

The incoming crew's travel time from the depot counts as paid sign-on time, and so does the outgoing crew's return. Both are included in spread-over.

Tests: U

## EC-LD-05 Split linked duty

The bus parks at the depot mid-day and the same crew returns for the evening portion.

Allowed when spread-over stays within the maximum. The duty type is `SPLIT`, and the unpaid gap follows the rule set.

Tests: U

## EC-LD-06 Non-monotone feasibility

An early cut fails but a later cut is feasible, because a long layover appears further along the block.

The builder checks all candidate cut points rather than stopping at the first failure.

Tests: U, P

## EC-LD-07 Tiny tail duty

Forty minutes are left at the end of a block.

The cut is chosen with a penalty when the remainder falls below `minPaidDuty`, so the earlier cut shifts to balance the two duties.

Tests: U

## EC-LD-08 Crew composition varies

Some buses and routes operate without a conductor.

Crew composition comes from the bus or route configuration, and slots are created only for the roles actually required.

Tests: U

---

# 5. Unlinked Duties and Handovers

## EC-UD-01 Pieces at different terminals

The outgoing piece ends at Terminal A and the next piece starts at Terminal B.

The gap must be at least the transfer time from A to B plus the handover buffer. Otherwise the combination is not allowed, and a forced manual combination raises `HANDOVER_INFEASIBLE`.

Tests: U, P

## EC-UD-02 Zero handover gap

The crew arrives at 10:00 and the next bus departs at 10:00.

Rejected. The minimum handover buffer applies even at the same stop.

Tests: U

## EC-UD-03 Too many bus changes

A crew would change buses four times in one duty.

Limited by `maxBusChangeoversPerDuty`, which is soft by default and can be made hard. The cost function also penalises changeovers.

Tests: U

## EC-UD-04 Duty ends away from the depot

Sign-off travel back to the depot is added to paid time and to spread-over.

Tests: U

## EC-UD-05 Break location has no facilities

A gap is long enough to count as a break, but only if taken somewhere with facilities.

Relief points carry a `hasCrewFacilities` flag, and a break counts only where it is true. This is configurable.

Tests: U

## EC-UD-06 A piece belongs to two duties, or to none

Every piece must belong to exactly one duty. This is enforced by a database unique constraint on `duty_piece.piece_id` and by a property test.

Orphaned pieces surface as `UNCOVERED_PIECE`.

Tests: P, IT

## EC-UD-07 Local search creates a hard violation

The move is rejected before it is applied. A property test asserts that no hard violation exists after improvement.

Tests: P

## EC-UD-08 Conductor handover takes longer

Ticket machine handover and cash reconciliation take time, so an extra `conductorHandoverBufferMin` applies to the conductor role.

Tests: U

## EC-UD-09 Different seeds give very different schedules

The seed is stored on the run, and the default seed is fixed per depot and date. Re-runs are therefore stable unless the user explicitly asks for a different seed.

Tests: U

## EC-UD-10 Local search budget expires immediately

The CPU is overloaded and no improvement iterations run.

The greedy solution, which is already legal, is returned. Metrics show `improvementIterations=0`.

Tests: U

---

# 6. Rest and Labour Rules

## EC-REST-01 Two short breaks instead of one

Two 15-minute breaks are taken rather than a single 30-minute break.

By default this does not reset continuous work, because the rule requires a single interval. A configurable flag allows cumulative breaks where policy permits.

Tests: U

## EC-REST-02 Break of 29 minutes 59 seconds

Not a qualifying break. The limit is inclusive at 30 minutes.

Tests: U

## EC-REST-03 Spread-over exceeded while work time is low

A split duty has 7 hours of work but a spread-over above 12 hours.

`SPREAD_OVER_EXCEEDED` is raised, independently of work time.

Tests: U

## EC-REST-04 Actual hours differ from planned

The crew worked overtime yesterday.

Weekly calculation uses actual hours where they are recorded, and planned hours otherwise.

Tests: IT

## EC-REST-05 No weekly rest day available

If assigning today would leave no rest day in the rolling 7 days, `WEEKLY_REST_MISSING` applies and the crew is not eligible today.

Tests: U

## EC-REST-06 Weekly off falls on a holiday or special operations day

Eligibility respects the weekly off unless the crew volunteers through an explicit availability record. The volunteering is audited.

Tests: U

## EC-REST-07 History missing in the first week of use

The loader flags `HISTORY_INCOMPLETE`.

Depending on configuration, either a conservative assumption applies, treating the crew as having worked the maximum, or an import of the last 7 days of manual rosters is required before scheduling.

Tests: IT

## EC-REST-08 Overtime enabled with a cap

Duty work may exceed the standard up to `maxOvertimeMin`. Anything above that is a hard violation. Overtime is counted in reports.

Tests: U

---

# 7. Crew Assignment

## EC-CA-01 Licence expired

The licence expires on the service date or earlier, which makes the crew ineligible with `LICENCE_INVALID`.

Licences expiring within 30 days surface through a filter:

```text
GET /api/v1/crew?licenceExpiringBefore=2026-10-17
```

Tests: U, IT

## EC-CA-02 Licence class does not match the bus

Ineligible. The required class is derived from the block's vehicle class, for example a heavy passenger vehicle licence.

Tests: U

## EC-CA-03 Half-day leave

Leave from 09:00 to 13:00 is stored as a `tstzrange`. Only duties overlapping that range are excluded.

Tests: U

## EC-CA-04 Electric bus duty without EV training

`QUALIFICATION_MISSING`.

Tests: U

## EC-CA-05 Crew transferred mid-week

`crew_depot_history` holds effective dates, and weekly hours carry over across depots.

Tests: IT

## EC-CA-06 More duties than eligible crew

The hardest slots, meaning those with the fewest candidates, are assigned first. The rest become `UNASSIGNED_DUTY` with a reason histogram:

```text
UNASSIGNED_DUTY
  9 INSUFFICIENT_REST
  6 ON_LEAVE
  3 LICENCE_INVALID
```

Tests: U

## EC-CA-07 More crew than duties

Extra crew go into the standby pool up to `standbyPoolPct`, and the remainder are off duty. Fairness decides who goes to standby.

Tests: U

## EC-CA-08 The same crew always gets night duties

The fairness score penalises a high recent night-duty count, the report shows the distribution, and `FAIRNESS_IMBALANCE` is raised above the threshold.

Tests: U

## EC-CA-09 Crew suspended or terminated after assignment

The status change triggers revalidation of future assignments and produces a conflict on published schedules.

Tests: IT

## EC-CA-10 Crew absent on the day

This is an operational case. The scheduler assigns from the standby pool through an override, with eligibility re-checked, and the absence is recorded for history.

Tests: IT

## EC-CA-11 Pairing preference conflicts with legality

Driver and conductor pairing is a soft preference only and never overrides hard eligibility.

Tests: U

## EC-CA-12 Override with a hard violation

Rejected with 422 and the violations listed. This applies to every role, including ADMIN.

Tests: IT, SEC

## EC-CA-13 Override with a soft violation

Allowed for MANAGER and ADMIN with a mandatory reason, and audited. Rejected with 403 for SCHEDULER.

Tests: SEC

## EC-CA-14 Cross-depot crew loan

An explicit `crew_loan` record is required.

The exclusion constraint still prevents overlaps across depots, because it is keyed on the crew member rather than on the depot.

Tests: IT

## EC-CA-15 Tie in fairness score

A deterministic tiebreak by employee code keeps output stable.

Tests: P

---

# 8. Schedule Lifecycle and Publishing

## EC-LC-01 Publish with open hard conflicts

Rejected with 422 `PUBLISH_BLOCKED`, listing conflict counts by type.

Tests: IT

## EC-LC-02 Publish called twice

A double click, or a retry after a timeout.

The operation is idempotent. The second call returns the same result, with no new version and no error.

Tests: CON, IT

## EC-LC-03 Publishing a new version over an old one

A single transaction flips the old version to `SUPERSEDED` and the new one to `PUBLISHED`. The partial unique index guarantees exactly one published schedule per depot and date.

Tests: IT

## EC-LC-04 New version overlaps the previous date's late duty

The exclusion constraint raises `23P01`, which aborts the publish. It is mapped to 409 naming the crew member and the overlapping time range.

Tests: IT, SQL

## EC-LC-05 Edit after validation

The state returns to `DRAFT`, and validation must run again before publishing.

Tests: U

## EC-LC-06 Attempt to modify a published schedule

Rejected with 409 `SCHEDULE_IMMUTABLE`. The client must create a new version as a copy of the published one.

Tests: IT

## EC-LC-07 Master data change affects many future schedules

A licence expiry invalidates 14 days of published schedules.

A single revalidation job batches the affected dates with a debounce, which avoids a revalidation storm.

Tests: IT

## EC-LC-08 Discarding a draft with manual overrides

Allowed with confirmation. The overrides remain in the audit log.

Tests: IT

---

# 9. Geospatial and Route Management

## EC-GEO-01 Swapped coordinates

GeoJSON coordinates given as `[lat, lon]` instead of `[lon, lat]`.

The geometry falls outside the service area and is rejected with a hint:

```text
coordinates appear swapped (expected [lon, lat])
```

Tests: U, IT

## EC-GEO-02 Wrong coordinate system

Geometry supplied in EPSG:3857 web mercator metres, or carrying a CRS member.

Values outside the valid longitude and latitude ranges are rejected. Only EPSG:4326 is accepted, and the message explains the expected CRS.

Tests: U

## EC-GEO-03 Too few distinct points

A LineString with fewer than two distinct points, or with consecutive duplicates.

Duplicates are removed. If fewer than two distinct points remain, the request is rejected with 422.

Tests: U

## EC-GEO-04 Invalid polygon for a zone or service area

`ST_IsValid` is checked, and the rejection includes `ST_IsValidReason`. An optional `makeValid=true` repairs the geometry, and the repair is reported back to the user.

Tests: IT

## EC-GEO-05 Length computed in degrees

All metric operations use `geom_utm` in EPSG:32643.

A unit test on a known 1 km line asserts that the computed length is approximately 1,000 m, which catches the mistake immediately.

Tests: IT

## EC-GEO-06 Parallel roads inside the buffer

A service lane beside a main road, or a flyover above a road.

The default 25 m buffer keeps false positives low. Shared-stop and direction signals are reported alongside the overlap, and planners can re-run with a smaller buffer. Buffer sensitivity is evaluated separately.

Tests: IT

## EC-GEO-07 Routes crossing at a junction

Segments shorter than `minSegmentM`, which defaults to 200 m, are ignored, so a crossing is not reported as overlap.

Tests: IT

## EC-GEO-08 Same corridor, opposite direction

The UP direction of the proposal runs along the DOWN direction of an existing route.

Reported with `same_direction = false`. Severity is computed per direction, and the summary counts the two cases separately.

Tests: IT

## EC-GEO-09 Loop or ring route

Start equals end, as on ring-road services.

`ST_IsClosed` detects it and the direction is `LOOP`. Direction comparison uses segment orientation rather than start and end positions, which would be meaningless on a loop.

Tests: IT

## EC-GEO-10 Route goes out and back on the same road

`ST_LineMerge(ST_UnaryUnion(g))` runs before length computation, so overlap is not double counted and the ratio stays at or below 1.

Tests: IT

## EC-GEO-11 Noisy GPS trace

Zig-zags and spikes in a traced polyline.

`ST_SimplifyPreserveTopology` at roughly 5 m runs together with spike detection, which looks for a vertex creating an implausible detour. The user is warned and the geometry is simplified.

Tests: IT

## EC-GEO-12 MultiLineString with gaps

Usually caused by map-matching failures.

`ST_LineMerge` is applied first. If gaps larger than 50 m remain, the request is rejected with 422 and the gap locations.

Tests: IT

## EC-GEO-13 Stop far from the route line

More than 50 m away.

A warning is returned, and `dist_from_start_m` is still computed with `ST_LineLocatePoint`. Strict mode blocks instead.

Tests: IT

## EC-GEO-14 Stop sequence out of order

The sequence numbers do not match position along the line.

Validation requires `dist_from_start_m` to be non-decreasing by `seq`, with loop routes allowed to wrap once. Otherwise the request is rejected with 422.

Tests: U

## EC-GEO-15 Huge geometry or request body

A geometry with 50,000 vertices.

A vertex cap, for example 5,000 after simplification, and a body size limit give 413 or 422. This protects database CPU from a single request.

Tests: U, SEC

## EC-GEO-16 Route entirely outside the service area

Rejected with 422 `OUTSIDE_SERVICE_AREA`.

Tests: IT

## EC-GEO-17 Comparing geometries with Java equals

Never used for business logic. Comparison uses `ST_Equals` or a tolerance-based check.

Tests: U

## EC-GEO-18 Overlap query for an already-stored pattern

Its own pattern id, and the other patterns of the same route, are excluded from the candidate set. Otherwise a route would always appear to duplicate itself.

Tests: IT

## EC-GEO-19 Coverage zone without population data

The area-based ratio is used, and the response states the weighting:

```text
weighting = AREA
```

Tests: IT

## EC-GEO-20 Stops edited during a coverage refresh

`REFRESH MATERIALIZED VIEW CONCURRENTLY` is used, which requires a unique index, and refreshes are debounced. Readers keep the previous snapshot until the new one is ready.

Tests: IT

## EC-GEO-21 Route number reused

A retired route number is assigned to a new route.

A partial unique index on `route_no` covers non-retired routes only, so history is preserved and the number can be reused.

Tests: IT

## EC-GEO-22 Proposal edited after analysis

The analysis is marked stale because the pattern version differs. It must be re-run before submission or decision.

Tests: IT

---

# 10. Data Import and Data Quality

## EC-DATA-01 Registration numbers in different formats

```text
DL1PC1234
DL 1PC 1234
dl-1pc-1234
```

All normalise to `DL1PC1234` before the uniqueness check. The original string is kept for display.

Tests: U

## EC-DATA-02 Employee codes lost leading zeros in Excel

A code of `00123` arrives as `123`.

Codes are stored as text. The import warns when code length does not match the configured pattern, and offers a mapping option.

Tests: U

## EC-DATA-03 Ambiguous date formats

`dd/MM/yyyy` against `MM/dd/yyyy`.

The import takes an explicit format parameter. Ambiguous values such as 03/04 are rejected when no format is given.

Tests: U

## EC-DATA-04 Non-UTF-8 CSV with Hindi names

For example a Windows-1252 export.

UTF-8 is required, a BOM is handled, and invalid byte sequences produce a row error with the line number.

Tests: U

## EC-DATA-05 Partial file errors

Three bad rows out of 5,000.

The default is all-or-nothing per file, with a full error report. `dryRun=true` validates without writing anything.

Tests: IT

## EC-DATA-06 Unknown foreign key

A crew row references depot code `DPT-99`, which does not exist.

The row fails with the unknown code listed. Nothing is created implicitly.

Tests: IT

## EC-DATA-07 Duplicate rows within one file

Detected inside the file before any database work, and reported with both line numbers.

Tests: U

## EC-DATA-08 Very large import

100,000 rows.

Parsing is streamed, writes are JDBC batched, memory stays bounded, and progress is reported.

Tests: LOAD

---

# 11. API, Pagination and Filtering

## EC-API-01 Oversized page

```text
size=100000
```

Clamped to 100, and the response reports the effective size.

Tests: IT

## EC-API-02 Invalid paging parameters

```text
page=-1
size=0
```

Rejected with 400 and a field error.

Tests: IT

## EC-API-03 Page beyond the last page

Returns 200 with empty content and a correct `totalElements`. This is not an error.

Tests: IT

## EC-API-04 Sorting by a forbidden or unknown field

```text
sort=passwordHash,asc
```

Rejected with 400 listing the allowed fields. The whitelist prevents both data probing and slow unindexed scans.

Tests: SEC, IT

## EC-API-05 Offset drift between page requests

Records are inserted while a client pages through results, causing duplicates or skips.

Sorting is stable thanks to the `id` tiebreaker, and high-churn collections use keyset cursors instead of offsets.

Tests: IT

## EC-API-06 Count query too slow

`includeTotal=false` returns a slice with `hasNext`. Keyset endpoints never count at all.

Tests: LOAD

## EC-API-07 Inverted range filter

```text
from=2026-09-20&to=2026-09-10
```

Rejected with 400 `INVALID_RANGE`.

Tests: U

## EC-API-08 Unknown enum value

```text
status=ACTVE
```

Rejected with 400 listing the allowed values.

Tests: IT

## EC-API-09 Search string containing wildcards

A `q` value containing `%` or `_` is escaped before the `LIKE`, and is always a bound parameter.

Tests: SEC

## EC-API-10 Malformed bounding box

`minLon` greater than `maxLon`, values out of range, or not four numbers.

Rejected with 400 and an explanation.

Tests: U

## EC-API-11 Client retries a run request after a timeout

An `Idempotency-Key` returns the original run rather than creating a duplicate. Without a key, the partial unique index still prevents a second active run, giving 409.

Tests: CON

## EC-API-12 PATCH without If-Match

Rejected with 428 Precondition Required. A stale `If-Match` gives 412.

Tests: IT

## EC-API-13 Large report export

CSV is streamed rather than materialised in memory, with timeouts configured.

Tests: LOAD

## EC-API-14 Invalid cursor

A tampered cursor, or one taken from another endpoint.

Rejected with 400 `INVALID_CURSOR`. Cursors are opaque and HMAC-signed, so tampering is detectable.

Tests: SEC

## EC-API-15 SSE client disconnects mid-run

The emitter is cleaned up and the run continues unaffected. The client can reconnect and read the latest status.

Tests: IT

## EC-API-16 Mass assignment attempt

A client sends `status=PUBLISHED`, `version`, `depotId` or `id` in a create body.

Request DTOs do not contain these fields, and unknown JSON properties are rejected with 400 because `FAIL_ON_UNKNOWN_PROPERTIES` is enabled.

Tests: SEC

---

# 12. Security and Access Control

## EC-SEC-01 Accessing another depot's resource by id

A scheduler at Depot A requests a bus belonging to Depot B.

Returns 404 rather than 403, so the existence of the id is not revealed.

Tests: SEC

## EC-SEC-02 Filtering by another depot

A scheduler at Depot A calls `/crew?depotId=B`.

Depot scope overrides the filter and an empty page is returned. Where an explicit other-depot filter is used, the behaviour is 404, and either choice is documented consistently.

Tests: SEC

## EC-SEC-03 User disabled while holding a valid token

The `token_version` check rejects the token within the cache TTL of 60 seconds, always before the 15-minute access-token expiry.

Tests: SEC

## EC-SEC-04 Stolen refresh token reused after rotation

Reuse detection revokes the entire token family, and the user must log in again. The event is audited as a security event.

Tests: SEC

## EC-SEC-05 Brute-force login

Rate limited per IP and per username, with lockout after 5 failures for 15 minutes. The error message is generic, so accounts cannot be enumerated.

Tests: SEC

## EC-SEC-06 Forged or invalid JWT

```text
alg=none
HS256 signed with the public key
expired token
wrong issuer or audience
```

All rejected. Only RS256 with configured keys is accepted, and `iss`, `aud` and `exp` are validated.

Tests: SEC

## EC-SEC-07 Scheduler attempts to publish

Rejected with 403.

Tests: SEC

## EC-SEC-08 User with multiple roles

For example PLANNER and SCHEDULER.

Permissions are the union of both roles. Depot scope still applies to depot-scoped operations.

Tests: SEC

## EC-SEC-09 Scheduler account with no depot

A misconfiguration.

Depot-scoped operations are denied, failing closed rather than open. The user listing warns the administrator.

Tests: SEC

## EC-SEC-10 Crew PII visible to the wrong role

Phone, address and licence details.

Response DTOs mask fields based on role, and logs never contain PII.

Tests: SEC

## EC-SEC-11 Attempt to alter audit records

UPDATE and DELETE are revoked on `audit_log` for the application database user, so the operation fails at the database level.

Tests: IT

## EC-SEC-12 Actuator endpoints exposed

Only `health` and `info` are public. Everything else sits on a management port or internal network.

Tests: SEC

## EC-SEC-13 CORS from an unknown origin

Rejected by the allow-list. There is no wildcard origin with credentials.

Tests: SEC

## EC-SEC-14 SQL injection through a spatial parameter

A malicious GeoJSON string.

Parameters are always bound, and GeoJSON is parsed and validated in Java before it reaches SQL.

Tests: SEC

## EC-SEC-15 Self-approval of a route proposal

A planner who also holds the MANAGER role approves their own proposal.

A configurable separation-of-duties rule requires the approver to differ from the submitter.

Tests: SEC

---

# 13. Concurrency and Consistency

## EC-CON-01 Two runs started for the same depot-day

The partial unique index means exactly one row is inserted. The other request gets 409 with the existing run id.

Tests: CON

## EC-CON-02 Two instances claim the same queued run

`FOR UPDATE SKIP LOCKED` ensures exactly one instance claims it.

Tests: CON

## EC-CON-03 Instance crashes mid-run

The heartbeat stops and the reaper marks the run `FAILED` with `WORKER_LOST` after 2 minutes.

Results are written in a single transaction, so no partial rows remain and the user can simply re-run.

Tests: IT

## EC-CON-04 Two overrides that are individually legal but overlap together

Two schedulers override assignments for the same crew member in different duties at the same time.

In draft, the override re-validates inside a transaction holding a crew-level advisory lock:

```text
pg_advisory_xact_lock(crew_id)
```

On publish, the exclusion constraint is the final guard.

Tests: CON

## EC-CON-05 Optimistic lock conflict

Rejected with 409 and the current version, so the client can reload and retry.

Tests: IT

## EC-CON-06 Master data changes while a snapshot loads

The snapshot is loaded in one `REPEATABLE READ` transaction, so it is internally consistent. Changes made after the snapshot trigger revalidation of the resulting draft.

Tests: IT

## EC-CON-07 Publish races with revalidation

Publish locks the schedule row with `FOR UPDATE`, and revalidation takes the same lock. They therefore run in order and never interleave.

Tests: CON

## EC-CON-08 Deadlocks during batch inserts

Inserts are ordered by table and key using `order_inserts`. A deadlock (`40P01`) is retried once with jitter.

Tests: LOAD

---

# 14. Performance and Scale

## EC-PERF-01 Full fleet scheduled at once

5,000+ buses and roughly 50,000 trips.

Work is partitioned by depot-day and processed by bounded parallel workers. Memory per run stays bounded because a snapshot covers one depot only.

Tests: LOAD

## EC-PERF-02 One unusually large depot

Local search has a time budget and the greedy construction is O(T x B), so the run still terminates. Duration is monitored through `scheduling.run.duration`.

Tests: LOAD

## EC-PERF-03 N+1 queries on list endpoints

For example, loading the depot for every bus.

DTO projections or entity graphs are used, and a Hibernate statistics assertion in tests caps the query count per request.

Tests: IT

## EC-PERF-04 Spatial query not using the index

Usually caused by calling `ST_Transform` inside a `WHERE` clause.

Queries use the precomputed `geom_utm` column instead, and `EXPLAIN` checks in tests or benchmarks confirm index usage.

Tests: LOAD

## EC-PERF-05 IDENTITY ids silently disabling batching

Sequence ids with pooled allocation are used, and a test asserts that batch statements actually occur.

Tests: IT

## EC-PERF-06 Report queries scanning months of data

Materialized views, monthly partitions and required date filters keep the scan bounded.

Tests: LOAD

## EC-PERF-07 Coverage over the whole city

Grid-cell precomputation replaces a runtime union of thousands of stop buffers.

Tests: LOAD

---

# 15. Reporting

## EC-REP-01 Report for a date with only a draft schedule

Drafts are excluded by default. `includeDraft=true`, available to MANAGER, returns them clearly labelled.

Tests: IT

## EC-REP-02 Superseded versions double counted

Reports aggregate published schedules only.

Tests: IT

## EC-REP-03 Service day against calendar day

A duty after midnight belongs to the previous service date.

Reports group by `service_date`, and the documented field name makes this explicit to the reader.

Tests: U

## EC-REP-04 Division by zero

A depot with no blocks.

`NULLIF` is used in SQL, and the ratio is returned as `null` rather than an error or `NaN`.

Tests: IT

## EC-REP-05 Materialized view stale right after publish

A debounced concurrent refresh runs on the publish event, and the response carries an `asOf` timestamp so the reader knows how fresh the number is.

Tests: IT

---

# 16. Operational Disruptions

These are mostly outside the first version's scope. This section defines the minimum behaviour expected.

## EC-OPS-01 Road closure or diversion

Events, VIP movement or waterlogging.

The planner creates a temporary pattern variant with validity dates, and the affected date is re-scheduled as a new version.

## EC-OPS-02 Strike or mass absenteeism

Unassigned-duty conflicts appear alongside a fleet and crew shortage report. Managers decide trip cancellations manually.

## EC-OPS-03 Real-time delay causes a missed handover

Out of scope, because there is no AVL feed. The scheduler records a manual override. A live-operations module is a candidate for a later version.

## EC-OPS-04 Sudden demand spike

A special event needs extra trips.

The planner adds trips to a date-specific timetable exception, then re-runs the schedule for that date.

---

# 17. Traceability

Every test that verifies an edge case is tagged with its ID:

```text
@Tag("EC-GEO-01")
```

The CI report lists edge-case IDs with pass or fail. Any ID with no test appears as a gap in the evaluation report, which is what stops this document from quietly drifting away from the test suite.
