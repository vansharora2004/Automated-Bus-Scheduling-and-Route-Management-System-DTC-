-- Phase 3 — master data: depots, stops, buses and crew.
--
-- Sequence increments match the Hibernate allocationSize of 50 on each entity. A mismatch would let
-- the two allocators hand out colliding ids.
CREATE SEQUENCE IF NOT EXISTS depot_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS stop_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS bus_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS bus_unavailability_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS crew_member_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS crew_depot_history_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS crew_leave_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Depot
-- ---------------------------------------------------------------------------
CREATE TABLE depot (
    id               BIGINT      PRIMARY KEY DEFAULT nextval('depot_seq'),
    code             TEXT        NOT NULL,
    name             TEXT        NOT NULL,
    location         geometry(Point, 4326) NOT NULL,
    parking_capacity INTEGER     CHECK (parking_capacity IS NULL OR parking_capacity > 0),
    charging_bays    INTEGER     NOT NULL DEFAULT 0 CHECK (charging_bays >= 0),
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT depot_location_valid CHECK (ST_IsValid(location))
);

-- Codes are entered by hand and compared case-insensitively, so uniqueness must be too.
-- A plain UNIQUE(code) would accept both 'DPT-01' and 'dpt-01'.
CREATE UNIQUE INDEX depot_code_uq ON depot (upper(code));
CREATE INDEX depot_location_gix ON depot USING GIST (location);

-- The depot table did not exist in Phase 2, so app_user.depot_id was created without a foreign key.
-- Now that it does, the reference is enforced.
ALTER TABLE app_user
    ADD CONSTRAINT app_user_depot_fk FOREIGN KEY (depot_id) REFERENCES depot (id);

-- ---------------------------------------------------------------------------
-- Stop
-- ---------------------------------------------------------------------------
CREATE TABLE stop (
    id                  BIGINT  PRIMARY KEY DEFAULT nextval('stop_seq'),
    code                TEXT    NOT NULL,
    name                TEXT    NOT NULL,
    location            geometry(Point, 4326) NOT NULL,

    -- Projected copy, maintained by the database. Distance and radius filters are expressed in
    -- metres, and transforming on every query would make the spatial index unusable.
    location_utm        geometry(Point, 32643)
                        GENERATED ALWAYS AS (ST_Transform(location, 32643)) STORED,

    is_terminal         BOOLEAN NOT NULL DEFAULT FALSE,
    is_relief_point     BOOLEAN NOT NULL DEFAULT FALSE,

    -- Whether a break may be taken here, used by duty building from Phase 7 (edge case EC-UD-05).
    has_crew_facilities BOOLEAN NOT NULL DEFAULT FALSE,

    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT stop_location_valid CHECK (ST_IsValid(location))
);

CREATE UNIQUE INDEX stop_code_uq ON stop (upper(code));
CREATE INDEX stop_location_utm_gix ON stop USING GIST (location_utm);
-- Partial indexes: terminals and relief points are a small minority of stops, and every scheduling
-- query asks only for those.
CREATE INDEX stop_terminal_idx ON stop (id) WHERE is_terminal;
CREATE INDEX stop_relief_point_idx ON stop (id) WHERE is_relief_point;

-- ---------------------------------------------------------------------------
-- Bus
-- ---------------------------------------------------------------------------
CREATE TABLE bus (
    id                  BIGINT  PRIMARY KEY DEFAULT nextval('bus_seq'),

    -- Normalised form, upper case with separators stripped, which is what uniqueness is checked on.
    registration_no     TEXT    NOT NULL,
    -- As entered, kept for display so a plate reads the way the depot wrote it.
    registration_no_raw TEXT    NOT NULL,

    fleet_no            TEXT    NOT NULL,
    depot_id            BIGINT  NOT NULL REFERENCES depot (id),
    bus_type            TEXT    NOT NULL,
    fuel_type           TEXT    NOT NULL,
    is_ac               BOOLEAN NOT NULL DEFAULT FALSE,
    capacity            INTEGER NOT NULL CHECK (capacity > 0),
    ev_range_km         INTEGER CHECK (ev_range_km IS NULL OR ev_range_km > 0),
    status              TEXT    NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT  NOT NULL DEFAULT 0,

    CONSTRAINT bus_type_known CHECK (bus_type IN ('STANDARD', 'LOW_FLOOR', 'MIDI', 'ARTICULATED')),
    CONSTRAINT bus_fuel_known CHECK (fuel_type IN ('CNG', 'ELECTRIC', 'DIESEL')),
    CONSTRAINT bus_status_known
        CHECK (status IN ('ACTIVE', 'UNDER_MAINTENANCE', 'BREAKDOWN', 'RETIRED')),

    -- An electric bus without a range cannot be scheduled: block building needs it to respect the
    -- range reserve from Phase 6, so the gap is refused at entry rather than discovered later.
    CONSTRAINT bus_ev_needs_range CHECK (fuel_type <> 'ELECTRIC' OR ev_range_km IS NOT NULL)
);

CREATE UNIQUE INDEX bus_registration_no_uq ON bus (registration_no);
CREATE UNIQUE INDEX bus_depot_fleet_no_uq ON bus (depot_id, upper(fleet_no));
-- Supports the common list query: one depot, filtered by status.
CREATE INDEX bus_depot_status_idx ON bus (depot_id, status);
CREATE INDEX bus_fuel_type_idx ON bus (fuel_type);

CREATE TABLE bus_unavailability (
    id         BIGINT      PRIMARY KEY DEFAULT nextval('bus_unavailability_seq'),
    bus_id     BIGINT      NOT NULL REFERENCES bus (id) ON DELETE CASCADE,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,

    -- Derived so that overlap tests stay index-assisted range queries. JPA maps the two endpoints,
    -- which avoids a custom Hibernate type for a PostgreSQL range while keeping the range itself
    -- available to SQL and to the GiST index.
    period     TSTZRANGE   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,

    reason     TEXT        NOT NULL,
    note       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT bus_unavailability_reason_known
        CHECK (reason IN ('MAINTENANCE', 'BREAKDOWN', 'CHARGING', 'OTHER')),
    CONSTRAINT bus_unavailability_ends_after_start CHECK (ends_at > starts_at)
);

-- btree_gist (installed in V1) is what allows an equality column and a range column in one GiST
-- index. This is also the index shape the Phase 9 exclusion constraints rely on.
CREATE INDEX bus_unavailability_gix ON bus_unavailability USING GIST (bus_id, period);

-- ---------------------------------------------------------------------------
-- Crew
-- ---------------------------------------------------------------------------
CREATE TABLE crew_member (
    id             BIGINT  PRIMARY KEY DEFAULT nextval('crew_member_seq'),

    -- Text, never numeric. Employee codes carry leading zeros that a spreadsheet export drops and an
    -- integer column would destroy permanently (edge case EC-DATA-02).
    employee_code  TEXT    NOT NULL,

    name           TEXT    NOT NULL,
    crew_role      TEXT    NOT NULL,
    depot_id       BIGINT  NOT NULL REFERENCES depot (id),
    licence_no     TEXT,
    licence_class  TEXT,
    licence_expiry DATE,
    status         TEXT    NOT NULL,
    weekly_off_dow INTEGER CHECK (weekly_off_dow IS NULL OR weekly_off_dow BETWEEN 1 AND 7),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT  NOT NULL DEFAULT 0,

    CONSTRAINT crew_role_known CHECK (crew_role IN ('DRIVER', 'CONDUCTOR')),
    CONSTRAINT crew_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT crew_licence_class_known
        CHECK (licence_class IS NULL OR licence_class IN ('LMV', 'HMV', 'HPMV')),

    -- A driver with no licence on file can never be assigned, so the record is refused at entry.
    CONSTRAINT crew_driver_needs_licence
        CHECK (crew_role <> 'DRIVER' OR (licence_no IS NOT NULL AND licence_expiry IS NOT NULL))
);

CREATE UNIQUE INDEX crew_member_employee_code_uq ON crew_member (upper(employee_code));
CREATE INDEX crew_member_depot_role_status_idx ON crew_member (depot_id, crew_role, status);
-- Partial: the licence-expiry report only ever looks at rows that have an expiry date.
CREATE INDEX crew_member_licence_expiry_idx ON crew_member (licence_expiry)
    WHERE licence_expiry IS NOT NULL;

CREATE TABLE crew_depot_history (
    id             BIGINT PRIMARY KEY DEFAULT nextval('crew_depot_history_seq'),
    crew_member_id BIGINT NOT NULL REFERENCES crew_member (id) ON DELETE CASCADE,
    depot_id       BIGINT NOT NULL REFERENCES depot (id),
    effective_from DATE   NOT NULL,
    effective_to   DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT crew_depot_history_range
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- A transfer mid-week must not rewrite history: scheduling a past date has to resolve the depot the
-- crew member actually belonged to then (edge case EC-CA-05).
CREATE INDEX crew_depot_history_idx
    ON crew_depot_history (crew_member_id, effective_from DESC);

CREATE TABLE crew_leave (
    id             BIGINT      PRIMARY KEY DEFAULT nextval('crew_leave_seq'),
    crew_member_id BIGINT      NOT NULL REFERENCES crew_member (id) ON DELETE CASCADE,
    starts_at      TIMESTAMPTZ NOT NULL,
    ends_at        TIMESTAMPTZ NOT NULL,

    -- Same derived-range approach as bus_unavailability. Leave is stored as an instant range rather
    -- than whole days so that a half-day absence excludes only the duties it overlaps
    -- (edge case EC-CA-03).
    period         TSTZRANGE   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,

    leave_type     TEXT        NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT crew_leave_type_known
        CHECK (leave_type IN ('CASUAL', 'SICK', 'EARNED', 'UNPAID', 'ABSENT')),
    CONSTRAINT crew_leave_ends_after_start CHECK (ends_at > starts_at)
);

CREATE INDEX crew_leave_gix ON crew_leave USING GIST (crew_member_id, period);

CREATE TABLE crew_qualification (
    crew_member_id BIGINT NOT NULL REFERENCES crew_member (id) ON DELETE CASCADE,
    code           TEXT   NOT NULL,
    valid_until    DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (crew_member_id, code)
);

CREATE INDEX crew_qualification_code_idx ON crew_qualification (code);
