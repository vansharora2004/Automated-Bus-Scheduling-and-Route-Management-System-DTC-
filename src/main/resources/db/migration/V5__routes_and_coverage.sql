-- Phase 4 — routes as geometry, overlap analysis and service coverage.

CREATE SEQUENCE IF NOT EXISTS route_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS route_pattern_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS route_overlap_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS coverage_zone_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS grid_cell_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS service_area_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Service area
-- ---------------------------------------------------------------------------
-- The operating boundary. Its real job is catching coordinates entered as "lat, lon": Delhi is
-- roughly 77E 28N, and swapping those gives latitude 77, which is a valid latitude in northern
-- Siberia. No range check can reject that, but a containment test can.
CREATE TABLE service_area (
    id         BIGINT      PRIMARY KEY DEFAULT nextval('service_area_seq'),
    name       TEXT        NOT NULL,
    geom       geometry(MultiPolygon, 4326) NOT NULL,
    geom_utm   geometry(MultiPolygon, 32643)
               GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT service_area_geom_valid CHECK (ST_IsValid(geom))
);

-- Only one area may be active, so validation never has to choose between two boundaries.
CREATE UNIQUE INDEX service_area_one_active ON service_area ((active)) WHERE active;
CREATE INDEX service_area_geom_utm_gix ON service_area USING GIST (geom_utm);

-- A default boundary is seeded rather than left empty. With no service area configured the
-- containment check would silently do nothing, which is the one failure mode this table exists to
-- prevent. This rectangle covers the NCT of Delhi generously and should be replaced with the real
-- administrative boundary before production use.
INSERT INTO service_area (name, geom)
VALUES (
    'Delhi NCT (approximate default)',
    ST_Multi(ST_GeomFromText(
        'POLYGON((76.80 28.30, 77.65 28.30, 77.65 28.95, 76.80 28.95, 76.80 28.30))', 4326)));

-- ---------------------------------------------------------------------------
-- Route and patterns
-- ---------------------------------------------------------------------------
CREATE TABLE route (
    id             BIGINT      PRIMARY KEY DEFAULT nextval('route_seq'),
    route_no       TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    depot_id       BIGINT      NOT NULL REFERENCES depot (id),
    status         TEXT        NOT NULL DEFAULT 'PROPOSED',
    effective_from DATE,
    decision_note  TEXT,
    submitted_by   TEXT,
    decided_by     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT route_status_known
        CHECK (status IN ('PROPOSED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'ACTIVE', 'RETIRED'))
);

-- Partial, so a retired route number can be reused by a new route while history is preserved
-- (edge case EC-GEO-21).
CREATE UNIQUE INDEX route_no_uq_live ON route (upper(route_no)) WHERE status <> 'RETIRED';
CREATE INDEX route_depot_status_idx ON route (depot_id, status);

CREATE TABLE route_pattern (
    id         BIGINT NOT NULL DEFAULT nextval('route_pattern_seq') PRIMARY KEY,
    route_id   BIGINT NOT NULL REFERENCES route (id) ON DELETE CASCADE,
    direction  TEXT   NOT NULL,
    geom       geometry(LineString, 4326) NOT NULL,

    -- Projected copy maintained by the database. Every metric operation - length, buffer, overlap -
    -- runs against this. Transforming at query time would make the GiST index unusable.
    geom_utm   geometry(LineString, 32643)
               GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED,
    length_m   DOUBLE PRECISION
               GENERATED ALWAYS AS (ST_Length(ST_Transform(geom, 32643))) STORED,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT route_pattern_direction_known CHECK (direction IN ('UP', 'DOWN', 'LOOP')),
    CONSTRAINT route_pattern_geom_ok CHECK (ST_IsValid(geom) AND ST_NPoints(geom) >= 2),
    CONSTRAINT route_pattern_direction_uq UNIQUE (route_id, direction)
);

CREATE INDEX route_pattern_geom_utm_gix ON route_pattern USING GIST (geom_utm);

CREATE TABLE pattern_stop (
    pattern_id        BIGINT  NOT NULL REFERENCES route_pattern (id) ON DELETE CASCADE,
    seq               INTEGER NOT NULL,
    stop_id           BIGINT  NOT NULL REFERENCES stop (id),
    dist_from_start_m DOUBLE PRECISION,
    PRIMARY KEY (pattern_id, seq),
    CONSTRAINT pattern_stop_seq_positive CHECK (seq > 0)
);

CREATE INDEX pattern_stop_stop_idx ON pattern_stop (stop_id);

CREATE TABLE running_time_band (
    pattern_id  BIGINT  NOT NULL REFERENCES route_pattern (id) ON DELETE CASCADE,
    day_type    TEXT    NOT NULL,
    from_sec    INTEGER NOT NULL,
    to_sec      INTEGER NOT NULL,
    running_sec INTEGER NOT NULL,
    PRIMARY KEY (pattern_id, day_type, from_sec),
    CONSTRAINT running_time_band_day_type_known
        CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY', 'HOLIDAY')),
    CONSTRAINT running_time_band_window CHECK (to_sec > from_sec),
    CONSTRAINT running_time_band_positive CHECK (running_sec > 0)
);

-- ---------------------------------------------------------------------------
-- Overlap analysis results
-- ---------------------------------------------------------------------------
CREATE TABLE route_overlap (
    id                  BIGINT      PRIMARY KEY DEFAULT nextval('route_overlap_seq'),
    proposed_pattern_id BIGINT      NOT NULL REFERENCES route_pattern (id) ON DELETE CASCADE,
    existing_pattern_id BIGINT      NOT NULL REFERENCES route_pattern (id) ON DELETE CASCADE,
    overlap_m           DOUBLE PRECISION NOT NULL,
    overlap_ratio       DOUBLE PRECISION NOT NULL,
    shared_stops        INTEGER     NOT NULL DEFAULT 0,
    same_direction      BOOLEAN     NOT NULL,
    severity            TEXT        NOT NULL,

    -- The pattern version the analysis was computed against. A pattern edited afterwards makes the
    -- result stale, and submitting on stale analysis is what this column prevents
    -- (edge case EC-GEO-22).
    proposed_version    BIGINT      NOT NULL DEFAULT 0,

    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT route_overlap_severity_known CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT route_overlap_distinct CHECK (proposed_pattern_id <> existing_pattern_id),
    CONSTRAINT route_overlap_pair_uq UNIQUE (proposed_pattern_id, existing_pattern_id)
);

CREATE INDEX route_overlap_proposed_idx ON route_overlap (proposed_pattern_id, overlap_ratio DESC);

-- ---------------------------------------------------------------------------
-- Coverage
-- ---------------------------------------------------------------------------
CREATE TABLE coverage_zone (
    id         BIGINT PRIMARY KEY DEFAULT nextval('coverage_zone_seq'),
    name       TEXT   NOT NULL,
    zone_type  TEXT   NOT NULL,
    geom       geometry(MultiPolygon, 4326) NOT NULL,
    geom_utm   geometry(MultiPolygon, 32643)
               GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED,

    -- Null where no population figure is available. Coverage then falls back to area weighting, and
    -- the response says which was used (edge case EC-GEO-19).
    population BIGINT CHECK (population IS NULL OR population >= 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coverage_zone_type_known CHECK (zone_type IN ('WARD', 'GRID', 'DISTRICT')),
    CONSTRAINT coverage_zone_geom_valid CHECK (ST_IsValid(geom))
);

CREATE INDEX coverage_zone_geom_utm_gix ON coverage_zone USING GIST (geom_utm);
CREATE INDEX coverage_zone_type_idx ON coverage_zone (zone_type);

CREATE TABLE grid_cell (
    id           BIGINT PRIMARY KEY DEFAULT nextval('grid_cell_seq'),
    geom         geometry(Polygon, 4326) NOT NULL,
    geom_utm     geometry(Polygon, 32643)
                 GENERATED ALWAYS AS (ST_Transform(geom, 32643)) STORED,
    centroid_utm geometry(Point, 32643)
                 GENERATED ALWAYS AS (ST_Centroid(ST_Transform(geom, 32643))) STORED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT grid_cell_geom_valid CHECK (ST_IsValid(geom))
);

CREATE INDEX grid_cell_geom_utm_gix ON grid_cell USING GIST (geom_utm);
CREATE INDEX grid_cell_centroid_gix ON grid_cell USING GIST (centroid_utm);

-- Per-cell distance to the nearest active stop.
--
-- Only the distance is stored, not a boolean. Storing "covered" would bake the catchment radius into
-- the view, so changing it from 500 m would need a migration; with the distance, the radius becomes a
-- query parameter and the same view answers every question.
--
-- Refreshed when stops change. Phase 11 owns the debounced refresh job.
CREATE MATERIALIZED VIEW cell_coverage AS
SELECT c.id AS cell_id,
       (SELECT min(ST_Distance(s.location_utm, c.centroid_utm))
        FROM stop s
        WHERE s.active) AS nearest_stop_m
FROM grid_cell c;

-- Unique index is not optional: REFRESH MATERIALIZED VIEW CONCURRENTLY requires one, and without
-- CONCURRENTLY a refresh locks readers out while stops are being edited (edge case EC-GEO-20).
CREATE UNIQUE INDEX cell_coverage_cell_id_uq ON cell_coverage (cell_id);
CREATE INDEX cell_coverage_nearest_idx ON cell_coverage (nearest_stop_m);

-- ---------------------------------------------------------------------------
-- Grid generation
-- ---------------------------------------------------------------------------
-- Fills grid_cell by tiling the active service area.
--
-- Cell size is a parameter rather than fixed: 250 m over Delhi is roughly 108,000 cells, which is the
-- right resolution for a coverage report and far too slow to regenerate inside a test. Tests pass a
-- coarser size and assert on ratios, which are unaffected.
--
-- Deliberately not a materialized view: the grid changes only when the service area does, which is
-- rare, while cell_coverage changes whenever a stop moves.
CREATE OR REPLACE FUNCTION generate_grid_cells(cell_size_m DOUBLE PRECISION)
RETURNS INTEGER AS $$
DECLARE
    cell_count INTEGER;
BEGIN
    IF cell_size_m <= 0 THEN
        RAISE EXCEPTION 'cell size must be positive, got %', cell_size_m;
    END IF;

    DELETE FROM grid_cell;

    INSERT INTO grid_cell (geom)
    SELECT ST_Transform(g.geom, 4326)
    FROM service_area a
    CROSS JOIN LATERAL ST_SquareGrid(cell_size_m, a.geom_utm) g
    WHERE a.active
      AND ST_Intersects(g.geom, a.geom_utm);

    SELECT count(*) INTO cell_count FROM grid_cell;
    RETURN cell_count;
END;
$$ LANGUAGE plpgsql;
