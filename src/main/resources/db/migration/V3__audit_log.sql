-- Phase 2 — append-only audit log.
--
-- Range-partitioned by month. The table grows for the life of the system and is queried almost
-- exclusively by time range, so monthly partitions keep both the indexes and the retention job
-- cheap. A partitioned table's primary key must contain the partition key, hence (id, at).
CREATE TABLE audit_log (
    id          BIGINT      NOT NULL DEFAULT nextval('audit_log_seq'),
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       TEXT,
    action      TEXT        NOT NULL,
    entity_type TEXT        NOT NULL,
    entity_id   TEXT,
    before      JSONB,
    after       JSONB,
    reason      TEXT,
    trace_id    TEXT,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

CREATE INDEX audit_log_at_idx ON audit_log (at DESC, id DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor, at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, at DESC);

-- Monthly partitions for the current and next calendar year.
--
-- Phase 11 adds the operational job that rolls new partitions forward and archives old ones.
-- Until then the DEFAULT partition below guarantees that a write can never fail for want of a
-- partition, which matters because losing an audit row is worse than a slightly untidy table.
DO $$
DECLARE
    start_month DATE := date_trunc('year', now())::date;
    m           DATE;
    part_name   TEXT;
BEGIN
    FOR i IN 0..23 LOOP
        m := start_month + (i || ' month')::interval;
        part_name := 'audit_log_' || to_char(m, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS audit_log_default PARTITION OF audit_log DEFAULT;
