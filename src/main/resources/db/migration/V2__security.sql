-- Phase 2 — authentication, roles and refresh tokens.

CREATE TABLE app_user (
    id            BIGINT      PRIMARY KEY DEFAULT nextval('app_user_seq'),
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,

    -- Null means an HQ user, who is not bound to a single depot and can see every depot.
    -- The foreign key to depot cannot exist yet, because the depot table arrives in Phase 3.
    -- Phase 3 adds it.
    depot_id      BIGINT,

    -- Bumped whenever the user is disabled or their roles or depot change. Access tokens carry
    -- the value they were issued with, so a stale token is rejected without a token blacklist.
    token_version INTEGER     NOT NULL DEFAULT 0,

    failed_logins INTEGER     NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0
);

-- Usernames are compared case-insensitively, so uniqueness has to be enforced the same way.
-- A plain UNIQUE(username) would happily accept both 'admin' and 'Admin'.
CREATE UNIQUE INDEX app_user_username_uq ON app_user (lower(username));

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role    TEXT   NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT user_role_known CHECK (role IN ('ADMIN', 'MANAGER', 'PLANNER', 'SCHEDULER'))
);

CREATE TABLE refresh_token (
    id          BIGINT      PRIMARY KEY DEFAULT nextval('refresh_token_seq'),
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,

    -- Only the hash is stored. A leaked database dump must not yield usable refresh tokens.
    token_hash  TEXT        NOT NULL UNIQUE,

    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,

    -- Set when this token is rotated. Presenting a token that already has a successor means the
    -- token was replayed, which revokes the whole family rather than just this row.
    replaced_by BIGINT      REFERENCES refresh_token (id),

    CONSTRAINT refresh_token_expiry_after_issue CHECK (expires_at > issued_at)
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id) WHERE NOT revoked;
CREATE INDEX refresh_token_expires_idx ON refresh_token (expires_at) WHERE NOT revoked;
