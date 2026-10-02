-- Phase 5 — timetables, generated trips, deadhead times and the calendar.

CREATE SEQUENCE IF NOT EXISTS timetable_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS trip_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS deadhead_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS calendar_exception_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Timetable
-- ---------------------------------------------------------------------------
CREATE TABLE timetable (
    id         BIGINT      PRIMARY KEY DEFAULT nextval('timetable_seq'),
    route_id   BIGINT      NOT NULL REFERENCES route (id),
    day_type   TEXT        NOT NULL,
    valid_from DATE        NOT NULL,

    -- Null means open-ended. A timetable usually runs until replaced rather than to a known date.
    valid_to   DATE,

    status     TEXT        NOT NULL DEFAULT 'DRAFT',

    -- Derived so the exclusion constraint below has a range to compare. Inclusive at both ends, because
    -- valid_to is the last day the timetable applies, not the first day it does not.
    validity   DATERANGE   GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[]')) STORED,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT timetable_day_type_known
        CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY', 'HOLIDAY')),
    CONSTRAINT timetable_status_known CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT timetable_validity_ordered CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- Two active timetables for one route and day type over overlapping dates would make trip generation
-- ambiguous: the system would have to pick one, and either choice is wrong half the time. The database
-- refuses the situation outright, using btree_gist to mix equality columns with a range.
ALTER TABLE timetable
    ADD CONSTRAINT timetable_no_overlapping_validity
    EXCLUDE USING gist (route_id WITH =, day_type WITH =, validity WITH &&)
    WHERE (status = 'ACTIVE');

CREATE INDEX timetable_route_day_type_idx ON timetable (route_id, day_type, status);

CREATE TABLE headway_band (
    timetable_id BIGINT  NOT NULL REFERENCES timetable (id) ON DELETE CASCADE,
    direction    TEXT    NOT NULL,
    from_sec     INTEGER NOT NULL,
    to_sec       INTEGER NOT NULL,
    headway_sec  INTEGER NOT NULL,

    -- Derived, so overlapping bands can be refused by an exclusion constraint rather than by
    -- application code that a future caller might bypass.
    window_range INT4RANGE GENERATED ALWAYS AS (int4range(from_sec, to_sec, '[)')) STORED,

    PRIMARY KEY (timetable_id, direction, from_sec),
    CONSTRAINT headway_band_direction_known CHECK (direction IN ('UP', 'DOWN', 'LOOP')),
    CONSTRAINT headway_band_window CHECK (to_sec > from_sec),
    CONSTRAINT headway_band_headway_positive CHECK (headway_sec > 0),

    -- Service-day seconds, which may run past midnight. 36 hours is a generous ceiling that still
    -- catches a value entered in minutes or milliseconds by mistake.
    CONSTRAINT headway_band_within_service_day CHECK (from_sec >= 0 AND to_sec <= 129600)
);

-- Overlapping bands for one direction would generate duplicate trips at the overlap
-- (edge case EC-TT-03).
ALTER TABLE headway_band
    ADD CONSTRAINT headway_band_no_overlap
    EXCLUDE USING gist (timetable_id WITH =, direction WITH =, window_range WITH &&);

-- ---------------------------------------------------------------------------
-- Trip
-- ---------------------------------------------------------------------------
CREATE TABLE trip (
    id                     BIGINT  PRIMARY KEY DEFAULT nextval('trip_seq'),
    timetable_id           BIGINT  NOT NULL REFERENCES timetable (id) ON DELETE CASCADE,
    pattern_id             BIGINT  NOT NULL REFERENCES route_pattern (id),
    start_stop_id          BIGINT  REFERENCES stop (id),
    end_stop_id            BIGINT  REFERENCES stop (id),

    -- Seconds from the start of the service day, GTFS style. Values above 86,400 are normal and mean
    -- the trip runs past midnight while still belonging to the earlier service date.
    start_sec              INTEGER NOT NULL,
    end_sec                INTEGER NOT NULL,

    distance_m             DOUBLE PRECISION,
    required_vehicle_class TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT trip_ends_after_start CHECK (end_sec > start_sec),
    CONSTRAINT trip_start_non_negative CHECK (start_sec >= 0),
    CONSTRAINT trip_vehicle_class_known
        CHECK (required_vehicle_class IS NULL
               OR required_vehicle_class IN ('STANDARD', 'LOW_FLOOR', 'MIDI', 'ARTICULATED')),

    -- Generation and a later manual edit can otherwise produce the same departure twice, which would
    -- have two buses dispatched for one trip (edge case EC-TT-05).
    CONSTRAINT trip_unique_departure UNIQUE (timetable_id, pattern_id, start_sec)
);

-- Supports the keyset-paginated trip list, which orders by id and never counts.
CREATE INDEX trip_timetable_start_idx ON trip (timetable_id, start_sec);
CREATE INDEX trip_pattern_idx ON trip (pattern_id);

-- ---------------------------------------------------------------------------
-- Deadhead matrix
-- ---------------------------------------------------------------------------
-- Non-revenue movement between two points, by time of day.
--
-- Time-banded because a cross-city move in the morning peak takes far longer than the same move at
-- midnight, and a single average would make peak blocks infeasible and off-peak blocks wasteful
-- (edge case EC-TT-11).
CREATE TABLE deadhead (
    id            BIGINT  PRIMARY KEY DEFAULT nextval('deadhead_seq'),
    from_stop_id  BIGINT  NOT NULL REFERENCES stop (id),
    to_stop_id    BIGINT  NOT NULL REFERENCES stop (id),
    from_sec_band INTEGER NOT NULL,
    travel_sec    INTEGER NOT NULL,
    distance_m    DOUBLE PRECISION,

    -- True when the value was derived from straight-line distance rather than measured. Planners need to
    -- know which numbers are guesses, and the scheduler raises a soft conflict for them
    -- (edge case EC-TT-10).
    estimated     BOOLEAN NOT NULL DEFAULT FALSE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT deadhead_travel_positive CHECK (travel_sec > 0),
    CONSTRAINT deadhead_band_non_negative CHECK (from_sec_band >= 0),
    CONSTRAINT deadhead_unique UNIQUE (from_stop_id, to_stop_id, from_sec_band)
);

CREATE INDEX deadhead_lookup_idx ON deadhead (from_stop_id, to_stop_id, from_sec_band);
CREATE INDEX deadhead_estimated_idx ON deadhead (estimated) WHERE estimated;

-- ---------------------------------------------------------------------------
-- Calendar exceptions
-- ---------------------------------------------------------------------------
-- Overrides the day type for a date, network-wide or for one depot.
--
-- Holidays cannot be derived from a date, so they only ever arrive here. A festival running a Sunday
-- timetable on a Wednesday is a calendar fact, not a calculation (edge case EC-TIME-05).
CREATE TABLE calendar_exception (
    id               BIGINT PRIMARY KEY DEFAULT nextval('calendar_exception_seq'),
    service_date     DATE   NOT NULL,

    -- Null means the whole network. A depot-specific row wins over a network-wide one for that depot.
    depot_id         BIGINT REFERENCES depot (id),

    day_type_override TEXT  NOT NULL,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT calendar_exception_day_type_known
        CHECK (day_type_override IN ('WEEKDAY', 'SATURDAY', 'SUNDAY', 'HOLIDAY'))
);

-- One override per date per scope. Two rows for the same date and depot would make resolution
-- ambiguous. Separate partial indexes because NULL depot_id is not comparable with = in a unique index.
CREATE UNIQUE INDEX calendar_exception_network_uq
    ON calendar_exception (service_date) WHERE depot_id IS NULL;
CREATE UNIQUE INDEX calendar_exception_depot_uq
    ON calendar_exception (service_date, depot_id) WHERE depot_id IS NOT NULL;
