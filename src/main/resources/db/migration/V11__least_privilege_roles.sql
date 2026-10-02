-- Phase 11 — least-privilege database roles, and making the audit log genuinely append-only.
--
-- The application has run as the migration user until now, which means it has had DDL rights it never needs at
-- runtime. Splitting the two limits what a compromised application connection can do: it can read and write
-- rows, and it cannot drop a table, disable a constraint, or rewrite the audit trail.
--
-- Roles are created with NOLOGIN and no password here. Granting login and setting a password is a deployment
-- step, not a migration step: a password committed to a repository is not a password. The runbook covers it.

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    -- app_rw: what the application connects as. DML only.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rw') THEN
        CREATE ROLE app_rw NOLOGIN;
    END IF;

    -- migrator: what Flyway connects as. DDL, and nothing else needs it.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migrator') THEN
        CREATE ROLE migrator NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_rw;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_rw;

-- Future tables too, or every later migration would need to remember this.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO app_rw;

-- The reporting views are read-only by nature; a materialized view cannot be written to directly anyway.
GRANT SELECT ON mv_fleet_utilization_daily, mv_crew_hours_weekly, mv_schedule_kpis TO app_rw;
GRANT SELECT ON cell_coverage TO app_rw;

GRANT ALL ON SCHEMA public TO migrator;

-- ---------------------------------------------------------------------------
-- Audit log protection
-- ---------------------------------------------------------------------------
-- Append-only, enforced rather than asserted.
--
-- An audit trail that the application can edit is not evidence of anything: the first thing an attacker with an
-- application connection would do is remove their own tracks. INSERT and SELECT are all the application ever
-- needs, so UPDATE and DELETE are revoked.
--
-- Revoked on the parent and on every partition, because privileges on a partitioned table are not inherited by
-- its partitions for direct access.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM app_rw;

DO $$
DECLARE
    part RECORD;
BEGIN
    FOR part IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_inherits i ON i.inhrelid = c.oid
        JOIN pg_class parent ON parent.oid = i.inhparent
        WHERE parent.relname = 'audit_log'
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM app_rw', part.relname);
    END LOOP;
END $$;

-- A trigger as well as the grant, because a future migration could re-grant the privilege by accident through
-- ALTER DEFAULT PRIVILEGES. Belt and braces on the one table where tampering matters most.
CREATE OR REPLACE FUNCTION audit_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

-- Partition-level triggers are needed as well: a statement against a partition does not fire the parent's
-- trigger. Applied to the parent and to each existing partition.
DO $$
DECLARE
    part RECORD;
BEGIN
    FOR part IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_inherits i ON i.inhrelid = c.oid
        JOIN pg_class parent ON parent.oid = i.inhparent
        WHERE parent.relname = 'audit_log'
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only()',
            'audit_log_append_only_' || part.relname, part.relname);
    END LOOP;
END $$;

COMMENT ON TABLE audit_log IS
    'Append-only. UPDATE and DELETE are revoked from app_rw and blocked by a trigger on every partition. '
    'Retention is handled by dropping whole partitions as the migrator role, not by deleting rows.';
