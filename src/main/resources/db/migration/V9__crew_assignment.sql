-- Phase 9 — crew assignment: named people on duties, with the double-booking guard in the database.

CREATE SEQUENCE IF NOT EXISTS duty_assignment_seq INCREMENT BY 50 START WITH 1;

-- ---------------------------------------------------------------------------
-- Duty assignment
-- ---------------------------------------------------------------------------
-- One named crew member on one duty in one role.
--
-- A duty needs a driver, and a conductor where the route requires one, so a duty can carry more than one
-- assignment. Keeping them as separate rows means a conductor can be replaced without touching the driver.
CREATE TABLE duty_assignment (
    id              BIGINT      PRIMARY KEY DEFAULT nextval('duty_assignment_seq'),
    duty_id         BIGINT      NOT NULL REFERENCES duty (id) ON DELETE CASCADE,
    crew_role       TEXT        NOT NULL,
    crew_member_id  BIGINT      NOT NULL REFERENCES crew_member (id),
    service_date    DATE        NOT NULL,

    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,

    -- Derived, and absolute rather than service-day seconds. A duty signing off at 01:20 belongs to the previous
    -- service date but overlaps the next date's early turn in real time, and only an absolute range makes that
    -- detectable.
    work_period     TSTZRANGE   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,

    -- Mirrors the owning schedule. Denormalised because the exclusion constraint below can only see columns of
    -- this table.
    schedule_status TEXT        NOT NULL DEFAULT 'DRAFT',

    status          TEXT        NOT NULL DEFAULT 'ASSIGNED',

    -- Required when a human overrides the assigner, and audited. An override without a stated reason is an
    -- unexplained change to somebody's working day.
    override_reason TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT duty_assignment_role_known CHECK (crew_role IN ('DRIVER', 'CONDUCTOR')),
    CONSTRAINT duty_assignment_status_known
        CHECK (status IN ('ASSIGNED', 'OVERRIDDEN', 'CANCELLED')),
    CONSTRAINT duty_assignment_times CHECK (ends_at > starts_at),
    -- One person per role per duty. Two drivers on one duty is not a redundancy, it is a mistake.
    CONSTRAINT duty_assignment_role_uq UNIQUE (duty_id, crew_role)
);

-- The guard this phase exists to make real.
--
-- A crew member can never hold two overlapping published duties, across dates and across depots. Application
-- checks can have bugs and two schedulers can race; this cannot be bypassed by either. Only published rows are
-- constrained, because drafts legitimately overlap the live schedule while a replacement is prepared, and
-- cancelled rows are not work anybody is doing.
ALTER TABLE duty_assignment
    ADD CONSTRAINT no_crew_double_booking
    EXCLUDE USING gist (crew_member_id WITH =, work_period WITH &&)
    WHERE (schedule_status = 'PUBLISHED' AND status <> 'CANCELLED');

CREATE INDEX duty_assignment_duty_idx ON duty_assignment (duty_id);
CREATE INDEX duty_assignment_crew_date_idx ON duty_assignment (crew_member_id, service_date DESC);
-- The crew history query: what this person worked over a rolling window.
CREATE INDEX duty_assignment_history_idx
    ON duty_assignment (crew_member_id, starts_at DESC)
    WHERE status <> 'CANCELLED';
