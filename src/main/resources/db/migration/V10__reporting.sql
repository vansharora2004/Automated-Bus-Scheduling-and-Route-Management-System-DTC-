-- Phase 10 — reporting: KPI materialized views over published schedules.
--
-- Three views, each aggregating one thing. Every one of them filters on status = 'PUBLISHED', which is the
-- single most important line in this file: a republished day has a SUPERSEDED version carrying the same
-- blocks and duties, and including it would double count every figure in every report.
--
-- Grouping is by service_date, never by a calendar date derived from a timestamp. A duty signing off at 01:20
-- belongs to the previous service date, and grouping by calendar day would move it into the wrong one.

-- ---------------------------------------------------------------------------
-- Fleet utilization, per depot and service date
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW mv_fleet_utilization_daily AS
SELECT s.depot_id,
       s.service_date,
       count(DISTINCT b.id)                                        AS blocks,
       count(DISTINCT a.bus_id)                                    AS buses_used,
       -- Peak vehicle requirement: the most blocks running at any one moment. Measured as the largest number
       -- of blocks whose pull-out to pull-in windows overlap, which is what a depot actually has to field.
       COALESCE(max(overlapping.concurrent), 0)                    AS peak_vehicles,
       COALESCE(sum(b.service_km), 0)                              AS service_km,
       COALESCE(sum(b.dead_km), 0)                                 AS dead_km,
       -- NULL rather than zero when nothing ran. A depot with no blocks has no ratio, and reporting 0% dead
       -- running for an empty day would look like a perfect day.
       CASE WHEN COALESCE(sum(b.service_km + b.dead_km), 0) > 0
            THEN sum(b.dead_km) / sum(b.service_km + b.dead_km)
            END                                                    AS dead_km_ratio,
       CASE WHEN COALESCE(sum(b.pull_in_sec - b.pull_out_sec), 0) > 0
            THEN sum(in_service.sec)::numeric / sum(b.pull_in_sec - b.pull_out_sec)
            END                                                    AS in_service_ratio
FROM schedule s
JOIN vehicle_block b ON b.schedule_id = s.id
LEFT JOIN bus_assignment a ON a.block_id = b.id
LEFT JOIN LATERAL (
    -- Revenue seconds inside this block, which is what in-service time means.
    SELECT COALESCE(sum(e.end_sec - e.start_sec), 0) AS sec
    FROM block_event e
    WHERE e.block_id = b.id AND e.type = 'TRIP'
) in_service ON TRUE
LEFT JOIN LATERAL (
    SELECT count(*) AS concurrent
    FROM vehicle_block other
    WHERE other.schedule_id = s.id
      AND other.pull_out_sec < b.pull_in_sec
      AND b.pull_out_sec < other.pull_in_sec
) overlapping ON TRUE
WHERE s.status = 'PUBLISHED'
GROUP BY s.depot_id, s.service_date;

-- A unique index is what REFRESH MATERIALIZED VIEW CONCURRENTLY requires. Without it a refresh takes an
-- exclusive lock and every report blocks behind it.
CREATE UNIQUE INDEX mv_fleet_utilization_daily_pk
    ON mv_fleet_utilization_daily (depot_id, service_date);
CREATE INDEX mv_fleet_utilization_daily_date_idx
    ON mv_fleet_utilization_daily (service_date DESC);

-- ---------------------------------------------------------------------------
-- Crew hours, per crew member and ISO week
-- ---------------------------------------------------------------------------
-- Weekly rather than daily, because the limits and the fairness questions are weekly. The ISO week is derived
-- from the service date for the same reason the grouping is: a duty running past midnight stays in its own
-- service week.
CREATE MATERIALIZED VIEW mv_crew_hours_weekly AS
SELECT da.crew_member_id,
       EXTRACT(ISOYEAR FROM da.service_date)::int                  AS iso_year,
       EXTRACT(WEEK FROM da.service_date)::int                     AS iso_week,
       s.depot_id,
       count(*)                                                    AS duties,
       COALESCE(sum(d.spread_sec - d.break_sec), 0)                AS work_sec,
       COALESCE(sum(d.paid_sec), 0)                                AS paid_sec,
       COALESCE(sum(d.platform_sec), 0)                            AS platform_sec,
       COALESCE(sum(d.overtime_sec), 0)                            AS overtime_sec,
       count(*) FILTER (WHERE d.duty_type = 'NIGHT')               AS night_duties,
       count(*) FILTER (WHERE d.duty_type = 'SPLIT')               AS split_duties
FROM duty_assignment da
JOIN duty d ON d.id = da.duty_id
JOIN schedule s ON s.id = d.schedule_id
WHERE s.status = 'PUBLISHED'
  AND da.status <> 'CANCELLED'
GROUP BY da.crew_member_id, EXTRACT(ISOYEAR FROM da.service_date), EXTRACT(WEEK FROM da.service_date),
         s.depot_id;

CREATE UNIQUE INDEX mv_crew_hours_weekly_pk
    ON mv_crew_hours_weekly (crew_member_id, iso_year, iso_week);
CREATE INDEX mv_crew_hours_weekly_depot_idx
    ON mv_crew_hours_weekly (depot_id, iso_year DESC, iso_week DESC);

-- ---------------------------------------------------------------------------
-- Schedule KPIs, per published schedule
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW mv_schedule_kpis AS
SELECT s.id                                                        AS schedule_id,
       s.depot_id,
       s.service_date,
       s.version_no,
       s.published_at,
       COALESCE(blocks.count, 0)                                   AS blocks,
       COALESCE(blocks.trips, 0)                                   AS trips,
       COALESCE(duties.count, 0)                                   AS duties,
       COALESCE(duties.assigned, 0)                                AS duties_assigned,
       COALESCE(duties.count, 0) - COALESCE(duties.assigned, 0)    AS duties_unassigned,
       COALESCE(duties.split_duties, 0)                            AS split_duties,
       COALESCE(duties.overtime_sec, 0)                            AS overtime_sec,
       -- Platform time as a share of paid time: the headline crew efficiency figure. NULL when nothing is
       -- paid, rather than a division by zero.
       CASE WHEN COALESCE(duties.paid_sec, 0) > 0
            THEN duties.platform_sec::numeric / duties.paid_sec
            END                                                    AS platform_to_paid_ratio,
       COALESCE(conflicts.hard, 0)                                 AS hard_conflicts,
       COALESCE(conflicts.soft, 0)                                 AS soft_conflicts
FROM schedule s
LEFT JOIN LATERAL (
    SELECT count(*) AS count,
           COALESCE(sum((SELECT count(*) FROM block_event e
                         WHERE e.block_id = b.id AND e.trip_id IS NOT NULL)), 0) AS trips
    FROM vehicle_block b WHERE b.schedule_id = s.id
) blocks ON TRUE
LEFT JOIN LATERAL (
    SELECT count(*) AS count,
           count(*) FILTER (WHERE EXISTS (
               SELECT 1 FROM duty_assignment da
               WHERE da.duty_id = d.id AND da.status <> 'CANCELLED'))              AS assigned,
           count(*) FILTER (WHERE d.duty_type = 'SPLIT')                           AS split_duties,
           COALESCE(sum(d.overtime_sec), 0)                                        AS overtime_sec,
           COALESCE(sum(d.paid_sec), 0)                                            AS paid_sec,
           COALESCE(sum(d.platform_sec), 0)                                        AS platform_sec
    FROM duty d WHERE d.schedule_id = s.id
) duties ON TRUE
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE c.severity = 'HARD') AS hard,
           count(*) FILTER (WHERE c.severity = 'SOFT') AS soft
    FROM conflict c WHERE c.schedule_id = s.id
) conflicts ON TRUE
WHERE s.status = 'PUBLISHED';

CREATE UNIQUE INDEX mv_schedule_kpis_pk ON mv_schedule_kpis (schedule_id);
CREATE INDEX mv_schedule_kpis_depot_date_idx ON mv_schedule_kpis (depot_id, service_date DESC);

-- ---------------------------------------------------------------------------
-- Supporting indexes for the live dashboard
-- ---------------------------------------------------------------------------
-- The dashboard reads live tables rather than the views, because "what is happening now" cannot wait for a
-- refresh. These indexes are what keep that read cheap.
CREATE INDEX IF NOT EXISTS schedule_published_date_idx
    ON schedule (service_date, depot_id) WHERE status = 'PUBLISHED';
CREATE INDEX IF NOT EXISTS duty_assignment_service_date_idx
    ON duty_assignment (service_date) WHERE status <> 'CANCELLED';
