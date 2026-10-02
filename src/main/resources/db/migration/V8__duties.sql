-- Phase 7 — crew duties: pieces of work, duties and handovers.
--
-- A bus block can run seventeen hours; a person cannot. These tables are where bus work becomes human work.

CREATE SEQUENCE IF NOT EXISTS piece_of_work_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS duty_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS handover_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Piece of work
-- ---------------------------------------------------------------------------
-- An unbroken stretch of one bus's day that one crew can take.
--
-- Delimited by event sequence as well as by time. The cut has to fall exactly on a relief opportunity, and
-- storing only times would let a later edit move a cut to a point where no crew can reach the bus.
CREATE TABLE piece_of_work (
    id                   BIGINT  PRIMARY KEY DEFAULT nextval('piece_of_work_seq'),
    block_id             BIGINT  NOT NULL REFERENCES vehicle_block (id) ON DELETE CASCADE,

    from_event_seq       INTEGER NOT NULL,
    to_event_seq         INTEGER NOT NULL,

    start_sec            INTEGER NOT NULL,
    end_sec              INTEGER NOT NULL,

    -- Null means the depot, which is where most pieces start and end. A nullable column rather than a
    -- synthetic depot stop row, because the depot is not a stop and pretending otherwise would make every
    -- stop query carry it.
    start_relief_stop_id BIGINT  REFERENCES stop (id),
    end_relief_stop_id   BIGINT  REFERENCES stop (id),

    CONSTRAINT piece_of_work_event_order CHECK (to_event_seq >= from_event_seq),
    CONSTRAINT piece_of_work_times CHECK (end_sec > start_sec),
    CONSTRAINT piece_of_work_unique_span UNIQUE (block_id, from_event_seq, to_event_seq)
);

CREATE INDEX piece_of_work_block_idx ON piece_of_work (block_id, from_event_seq);

-- ---------------------------------------------------------------------------
-- Duty
-- ---------------------------------------------------------------------------
-- One person's working day, before anyone is named.
--
-- The metrics are stored rather than derived on read. They were computed against the rule set that was in
-- force for the run, and recomputing them under today's rules would answer a different question and quietly
-- rewrite what a crew was paid for.
CREATE TABLE duty (
    id            BIGINT  PRIMARY KEY DEFAULT nextval('duty_seq'),
    schedule_id   BIGINT  NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    duty_no       INTEGER NOT NULL,
    mode          TEXT    NOT NULL,
    duty_type     TEXT    NOT NULL,

    -- Service-day seconds, so a duty signing off at 01:20 reads as 90,000 and still belongs to this date.
    sign_on_sec   INTEGER NOT NULL,
    sign_off_sec  INTEGER NOT NULL,

    -- Time actually with a bus, excluding unpaid breaks.
    platform_sec  INTEGER NOT NULL,

    -- What the crew is paid for, never below the minimum paid guarantee.
    paid_sec      INTEGER NOT NULL,

    break_sec     INTEGER NOT NULL DEFAULT 0,

    -- Sign-on to sign-off, including unpaid breaks. This is the figure the spread-over rule limits.
    spread_sec    INTEGER NOT NULL,

    overtime_sec  INTEGER NOT NULL DEFAULT 0,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT  NOT NULL DEFAULT 0,

    CONSTRAINT duty_mode_known CHECK (mode IN ('LINKED', 'UNLINKED')),
    CONSTRAINT duty_type_known CHECK (duty_type IN ('EARLY', 'MIDDLE', 'LATE', 'NIGHT', 'SPLIT')),
    CONSTRAINT duty_times CHECK (sign_off_sec > sign_on_sec),
    CONSTRAINT duty_metrics_non_negative
        CHECK (platform_sec >= 0 AND paid_sec >= 0 AND break_sec >= 0 AND overtime_sec >= 0),
    -- The spread-over is the whole duty, so nothing inside it can be longer.
    CONSTRAINT duty_spread_covers_platform CHECK (spread_sec >= platform_sec),
    CONSTRAINT duty_no_uq UNIQUE (schedule_id, duty_no)
);

CREATE INDEX duty_schedule_idx ON duty (schedule_id, duty_no);
CREATE INDEX duty_schedule_type_idx ON duty (schedule_id, duty_type);

-- ---------------------------------------------------------------------------
-- Duty piece
-- ---------------------------------------------------------------------------
CREATE TABLE duty_piece (
    duty_id  BIGINT  NOT NULL REFERENCES duty (id) ON DELETE CASCADE,
    seq      INTEGER NOT NULL,
    piece_id BIGINT  NOT NULL REFERENCES piece_of_work (id) ON DELETE CASCADE,

    PRIMARY KEY (duty_id, seq),

    -- A piece of work belongs to exactly one duty. Two duties covering the same stretch of a bus would mean
    -- two crews turning up for it, and nothing downstream would notice.
    CONSTRAINT duty_piece_once UNIQUE (piece_id)
);

CREATE INDEX duty_piece_piece_idx ON duty_piece (piece_id);

-- ---------------------------------------------------------------------------
-- Handover
-- ---------------------------------------------------------------------------
-- One crew taking a bus over from another.
--
-- Its own row rather than something inferred from two adjacent duties: a handover is an operational event with
-- a place and a time that someone has to physically be at, and the list a depot works from in the morning is a
-- list of these.
CREATE TABLE handover (
    id               BIGINT  PRIMARY KEY DEFAULT nextval('handover_seq'),
    schedule_id      BIGINT  NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    block_id         BIGINT  NOT NULL REFERENCES vehicle_block (id) ON DELETE CASCADE,

    -- Null means the depot.
    relief_stop_id   BIGINT  REFERENCES stop (id),

    at_sec           INTEGER NOT NULL,
    outgoing_duty_id BIGINT  NOT NULL REFERENCES duty (id) ON DELETE CASCADE,
    incoming_duty_id BIGINT  NOT NULL REFERENCES duty (id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT handover_distinct_duties CHECK (outgoing_duty_id <> incoming_duty_id),
    CONSTRAINT handover_at_non_negative CHECK (at_sec >= 0),
    -- One handover per bus per moment. Two rows for the same point would double-count a crew change in every
    -- report built on them.
    CONSTRAINT handover_unique_point UNIQUE (block_id, at_sec)
);

CREATE INDEX handover_schedule_idx ON handover (schedule_id, at_sec);
CREATE INDEX handover_outgoing_idx ON handover (outgoing_duty_id);
CREATE INDEX handover_incoming_idx ON handover (incoming_duty_id);
