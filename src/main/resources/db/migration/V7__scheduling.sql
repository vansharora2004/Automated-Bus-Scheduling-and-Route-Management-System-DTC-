-- Phase 6 — vehicle scheduling: rule sets, the run queue, schedules, blocks and assignments.

CREATE SEQUENCE IF NOT EXISTS rule_set_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS schedule_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS vehicle_block_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS bus_assignment_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS conflict_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Rule set
-- ---------------------------------------------------------------------------
-- Labour and operational rules as versioned data rather than code.
--
-- Stored as JSONB and bound to a typed record. The alternative, a column per rule, would mean a
-- migration every time a rule is added and would still not let two depots differ.
CREATE TABLE rule_set (
    id             BIGINT      PRIMARY KEY DEFAULT nextval('rule_set_seq'),
    name           TEXT        NOT NULL,

    -- Null means global. A depot-specific set takes precedence over the global one for the same date.
    depot_id       BIGINT      REFERENCES depot (id),

    effective_from DATE        NOT NULL,
    rules          JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT      NOT NULL DEFAULT 0
);

-- Two rule sets with the same scope and date would make resolution ambiguous, and the engine would
-- silently pick one. Partial indexes because a NULL depot_id is not equal to itself in a plain
-- unique index, so one index cannot cover both scopes.
CREATE UNIQUE INDEX rule_set_global_uq ON rule_set (effective_from) WHERE depot_id IS NULL;
CREATE UNIQUE INDEX rule_set_depot_uq ON rule_set (depot_id, effective_from) WHERE depot_id IS NOT NULL;
CREATE INDEX rule_set_resolution_idx ON rule_set (effective_from DESC);

-- The statutory baseline. Every value is configurable; the statutory ones must be confirmed against
-- the rules in force before this system is used to roster real people.
INSERT INTO rule_set (name, depot_id, effective_from, rules)
VALUES (
    'Statutory default',
    NULL,
    DATE '2000-01-01',
    '{
      "maxWorkPerDutyMin": 480,
      "maxContinuousWorkMin": 300,
      "minBreakMin": 30,
      "maxSpreadOverMin": 720,
      "maxWeeklyWorkMin": 2880,
      "weeklyRestDaysPer7": 1,
      "minRestBetweenDutiesMin": 600,
      "signOnMin": 15,
      "signOffMin": 10,
      "minLayoverMin": 5,
      "minLayoverPct": 10,
      "handoverBufferMin": 5,
      "maxPiecesPerDuty": 3,
      "maxBusChangeoversPerDuty": 2,
      "targetWorkPerDutyMin": 450,
      "minPaidDutyMin": 240,
      "allowOvertime": false,
      "maxOvertimeMin": 60,
      "midDayDepotReturnGapMin": 90,
      "evRangeReservePct": 15,
      "standbyPoolPct": 5,
      "maxBlockDurationMin": 1140,
      "evChargingMin": 45
    }'::jsonb);

-- ---------------------------------------------------------------------------
-- Schedule run
-- ---------------------------------------------------------------------------
-- A queued unit of scheduling work for one depot and one service date.
--
-- UUID rather than a sequence: the id is handed to a client as a job handle, and a guessable
-- sequential handle lets one scheduler poll another's runs.
CREATE TABLE schedule_run (
    id              UUID        PRIMARY KEY,
    depot_id        BIGINT      NOT NULL REFERENCES depot (id),
    service_date    DATE        NOT NULL,
    mode            TEXT        NOT NULL,
    rule_set_id     BIGINT      NOT NULL REFERENCES rule_set (id),

    -- Stored so a run can be reproduced exactly. Determinism also depends on stable iteration order,
    -- which the engine provides by sorting rather than by relying on hash order.
    seed            BIGINT      NOT NULL,

    status          TEXT        NOT NULL DEFAULT 'QUEUED',

    -- Which worker holds the run, and when it last said so. Together these let the reaper tell a
    -- slow run from a dead one.
    claimed_by      TEXT,
    heartbeat_at    TIMESTAMPTZ,

    progress        INTEGER     NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    metrics         JSONB,
    error           TEXT,

    -- Lets a client retry a submission without creating a second run. A retry carrying the same key
    -- gets the original run back, which is what makes the endpoint safe behind a flaky network.
    idempotency_key TEXT,

    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    schedule_id     BIGINT,

    CONSTRAINT schedule_run_mode_known CHECK (mode IN ('LINKED', 'UNLINKED')),
    CONSTRAINT schedule_run_status_known
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

-- One queued or running run per depot and date. Two concurrent runs would race to write a schedule
-- for the same depot-day and burn CPU producing an answer one of them would discard.
CREATE UNIQUE INDEX one_active_run_per_depot_day
    ON schedule_run (depot_id, service_date)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE UNIQUE INDEX schedule_run_idempotency_uq
    ON schedule_run (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- The worker's claim query: oldest queued run first.
CREATE INDEX schedule_run_queue_idx ON schedule_run (status, created_at);
-- The reaper's query: running runs whose heartbeat has gone quiet.
CREATE INDEX schedule_run_heartbeat_idx ON schedule_run (heartbeat_at) WHERE status = 'RUNNING';
CREATE INDEX schedule_run_depot_date_idx ON schedule_run (depot_id, service_date, created_at DESC);

-- ---------------------------------------------------------------------------
-- Schedule
-- ---------------------------------------------------------------------------
CREATE TABLE schedule (
    id                 BIGINT      PRIMARY KEY DEFAULT nextval('schedule_seq'),
    run_id             UUID        NOT NULL REFERENCES schedule_run (id),
    depot_id           BIGINT      NOT NULL REFERENCES depot (id),
    service_date       DATE        NOT NULL,

    -- Versions accumulate rather than overwrite: a published schedule is immutable, so correcting it
    -- means publishing a later version and superseding the earlier one.
    version_no         INTEGER     NOT NULL,

    status             TEXT        NOT NULL DEFAULT 'DRAFT',

    -- Set by revalidation when master data changes under a schedule that was already validated or
    -- published, for example a bus breaking down.
    needs_revalidation BOOLEAN     NOT NULL DEFAULT FALSE,

    published_at       TIMESTAMPTZ,
    published_by       TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT schedule_status_known
        CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'SUPERSEDED', 'DISCARDED')),
    CONSTRAINT schedule_version_positive CHECK (version_no > 0),
    CONSTRAINT schedule_version_uq UNIQUE (depot_id, service_date, version_no)
);

-- Two published schedules for one depot-day would leave a crew with two rosters and no way to tell
-- which is real.
CREATE UNIQUE INDEX one_published_schedule_per_depot_day
    ON schedule (depot_id, service_date)
    WHERE status = 'PUBLISHED';

CREATE INDEX schedule_depot_date_idx ON schedule (depot_id, service_date DESC, version_no DESC);
CREATE INDEX schedule_run_idx ON schedule (run_id);

ALTER TABLE schedule_run
    ADD CONSTRAINT schedule_run_schedule_fk FOREIGN KEY (schedule_id) REFERENCES schedule (id);

-- ---------------------------------------------------------------------------
-- Vehicle block
-- ---------------------------------------------------------------------------
-- One bus's day: a chain of trips with the dead running and layovers between them.
CREATE TABLE vehicle_block (
    id            BIGINT  PRIMARY KEY DEFAULT nextval('vehicle_block_seq'),
    schedule_id   BIGINT  NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    block_no      INTEGER NOT NULL,
    vehicle_class TEXT,
    pull_out_sec  INTEGER NOT NULL,
    pull_in_sec   INTEGER NOT NULL,
    service_km    DOUBLE PRECISION NOT NULL DEFAULT 0,
    dead_km       DOUBLE PRECISION NOT NULL DEFAULT 0,

    CONSTRAINT vehicle_block_times CHECK (pull_in_sec > pull_out_sec),
    CONSTRAINT vehicle_block_no_uq UNIQUE (schedule_id, block_no)
);

CREATE INDEX vehicle_block_schedule_idx ON vehicle_block (schedule_id, block_no);

-- ---------------------------------------------------------------------------
-- Block event
-- ---------------------------------------------------------------------------
-- Every leg of a block, in order: pull-out, trips, dead running, layovers, depot parking, charging
-- and pull-in.
--
-- Range-partitioned by service date. At roughly 120,000 rows per published date this is the
-- fastest-growing operational table, and it is queried a date at a time. A partitioned table's
-- primary key must contain the partition key, hence the service date in the key.
CREATE TABLE block_event (
    block_id              BIGINT  NOT NULL REFERENCES vehicle_block (id) ON DELETE CASCADE,
    seq                   INTEGER NOT NULL,
    service_date          DATE    NOT NULL,
    type                  TEXT    NOT NULL,

    -- Set only on TRIP events. The other types are movement or idle time, which no trip describes.
    trip_id               BIGINT  REFERENCES trip (id),

    from_stop_id          BIGINT  REFERENCES stop (id),
    to_stop_id            BIGINT  REFERENCES stop (id),
    start_sec             INTEGER NOT NULL,
    end_sec               INTEGER NOT NULL,
    distance_m            DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Whether a crew could change over here. Computed once during scheduling rather than re-derived
    -- on every duty query, because it depends on the rule set that was in force for the run.
    is_relief_opportunity BOOLEAN NOT NULL DEFAULT FALSE,

    PRIMARY KEY (block_id, seq, service_date),

    CONSTRAINT block_event_type_known
        CHECK (type IN ('PULL_OUT', 'TRIP', 'DEADHEAD', 'LAYOVER', 'DEPOT_PARK', 'CHARGING', 'PULL_IN')),
    CONSTRAINT block_event_times CHECK (end_sec >= start_sec),
    CONSTRAINT block_event_trip_only_on_trip_type
        CHECK ((type = 'TRIP') = (trip_id IS NOT NULL))
) PARTITION BY RANGE (service_date);

CREATE INDEX block_event_block_idx ON block_event (block_id, seq);
CREATE INDEX block_event_trip_idx ON block_event (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX block_event_relief_idx ON block_event (block_id, start_sec) WHERE is_relief_opportunity;

-- Monthly partitions across the same two-year window the audit log uses, plus a DEFAULT so a write
-- can never fail for want of a partition. Phase 11 adds the job that rolls these forward.
DO $$
DECLARE
    start_month DATE := date_trunc('year', now())::date;
    m           DATE;
    part_name   TEXT;
BEGIN
    FOR i IN 0..23 LOOP
        m := start_month + (i || ' month')::interval;
        part_name := 'block_event_' || to_char(m, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF block_event FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS block_event_default PARTITION OF block_event DEFAULT;

-- ---------------------------------------------------------------------------
-- Bus assignment
-- ---------------------------------------------------------------------------
CREATE TABLE bus_assignment (
    id              BIGINT      PRIMARY KEY DEFAULT nextval('bus_assignment_seq'),
    block_id        BIGINT      NOT NULL REFERENCES vehicle_block (id) ON DELETE CASCADE,
    bus_id          BIGINT      NOT NULL REFERENCES bus (id),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,

    -- Derived, so double-booking can be refused by an exclusion constraint instead of by application
    -- code a future caller might bypass. Absolute rather than service-day seconds, because a block
    -- running past midnight must be comparable with the next service date's early block.
    period          TSTZRANGE   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,

    -- Mirrors the owning schedule's status. Denormalised on purpose: the exclusion constraint below
    -- can only see columns of this table.
    schedule_status TEXT        NOT NULL DEFAULT 'DRAFT',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT bus_assignment_block_uq UNIQUE (block_id),
    CONSTRAINT bus_assignment_times CHECK (ends_at > starts_at)
);

-- A bus cannot be in two published blocks at once. Only published rows are constrained: drafts
-- legitimately overlap the live schedule while a replacement is being prepared, and are validated in
-- the application instead.
ALTER TABLE bus_assignment
    ADD CONSTRAINT no_bus_double_booking
    EXCLUDE USING gist (bus_id WITH =, period WITH &&)
    WHERE (schedule_status = 'PUBLISHED');

CREATE INDEX bus_assignment_bus_idx ON bus_assignment (bus_id, starts_at);

-- ---------------------------------------------------------------------------
-- Conflict
-- ---------------------------------------------------------------------------
-- Everything wrong with a schedule, found by the independent validation pass.
--
-- Stored rather than recomputed on read: a conflict is evidence about a specific version, and a
-- recomputation against today's master data would answer a different question.
CREATE TABLE conflict (
    id          BIGINT      PRIMARY KEY DEFAULT nextval('conflict_seq'),
    schedule_id BIGINT      NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    type        TEXT        NOT NULL,
    severity    TEXT        NOT NULL,

    -- Which rows the conflict is about, as {entityType: id}. JSONB because the shape differs per
    -- conflict type and a column per possible reference would be mostly nulls.
    entity_refs JSONB,

    message     TEXT        NOT NULL,
    details     JSONB,
    resolved    BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_by TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT conflict_severity_known CHECK (severity IN ('HARD', 'SOFT'))
);

CREATE INDEX conflict_schedule_idx ON conflict (schedule_id, severity, type);
-- Publication is gated on there being no unresolved hard conflicts, which is this exact query.
CREATE INDEX conflict_blocking_idx ON conflict (schedule_id) WHERE severity = 'HARD' AND NOT resolved;
