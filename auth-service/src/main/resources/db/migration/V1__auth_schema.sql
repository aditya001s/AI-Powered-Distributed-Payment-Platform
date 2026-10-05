-- =============================================================================
-- auth-service : initial schema
--
-- Flyway owns this schema. Hibernate runs with ddl-auto=validate, so it verifies
-- the mapping against these tables and refuses to start on a mismatch rather than
-- silently altering production tables.
--
-- Conventions used throughout the platform:
--   * TIMESTAMPTZ, never TIMESTAMP. A naive timestamp is ambiguous the moment two
--     replicas run in different zones, and impossible to interpret after the fact.
--   * Money is BIGINT minor units (none in this service, but the rule is global).
--   * Public identifiers (user_id) are opaque and random, never sequential, so
--     they cannot be enumerated and do not leak row counts.
--   * Every table carries created_at; mutable tables also carry updated_at and a
--     version column for optimistic locking.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                    BIGSERIAL      PRIMARY KEY,

    -- Public identifier used in JWT `sub`, in events, and by every other service.
    user_id               VARCHAR(40)    NOT NULL,

    -- Stored lowercase (normalised in the application) so uniqueness and lookup
    -- are case-insensitive without needing a functional index.
    email                 VARCHAR(254)   NOT NULL,

    -- Algorithm-prefixed hash, e.g. {bcrypt}$2a$12$...
    -- Sized for the prefix plus a 60-character BCrypt digest, with room for a
    -- future algorithm migration.
    password_hash         VARCHAR(120)   NOT NULL,

    full_name             VARCHAR(120)   NOT NULL,
    phone                 VARCHAR(24),

    status                VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE',
    kyc_level             VARCHAR(16)    NOT NULL DEFAULT 'BASIC',

    -- Lockout state lives on the row, not in a cache, so it survives a restart
    -- and is consistent across replicas. An attacker must not be able to reset
    -- the counter by having their requests land on a different pod.
    failed_login_attempts INTEGER        NOT NULL DEFAULT 0,
    last_failed_login_at  TIMESTAMPTZ,
    locked_until          TIMESTAMPTZ,

    last_login_at         TIMESTAMPTZ,
    last_login_ip         VARCHAR(64),

    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version               BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_user_id UNIQUE (user_id),
    CONSTRAINT uq_users_email   UNIQUE (email),

    -- Enum values are constrained in the database as well as in Java. Hibernate
    -- writes the name of the enum constant; a CHECK means a bad value from any
    -- other client (a migration, a manual fix) is rejected too.
    CONSTRAINT ck_users_status CHECK (status IN
        ('ACTIVE', 'LOCKED', 'DISABLED', 'PENDING_VERIFICATION')),
    CONSTRAINT ck_users_kyc_level CHECK (kyc_level IN ('NONE', 'BASIC', 'FULL')),

    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0),
    -- Cheap sanity check that catches an obviously wrong email before it becomes
    -- a support ticket. Deliberately not a full RFC 5322 regex: those are
    -- unmaintainable and reject valid addresses.
    CONSTRAINT ck_users_email_shape CHECK (email LIKE '%@%.%' AND email = lower(email))
);

COMMENT ON TABLE  users IS 'Platform identities. Credentials are stored only as salted hashes.';
COMMENT ON COLUMN users.user_id IS 'Opaque public identifier. Appears in JWT sub and in all cross-service references.';
COMMENT ON COLUMN users.password_hash IS 'Algorithm-prefixed hash. NEVER a plaintext or reversible value.';
COMMENT ON COLUMN users.locked_until IS 'Compared against now() on every login, so a lock expires on time even if the sweep job is not running.';

-- Login is the hottest query in this service.
CREATE INDEX idx_users_status ON users (status);
-- Partial index: only locked rows matter to the unlock sweep, which keeps the
-- index tiny regardless of how many users exist.
CREATE INDEX idx_users_locked_until ON users (locked_until) WHERE locked_until IS NOT NULL;
CREATE INDEX idx_users_created_at ON users (created_at DESC);

-- -----------------------------------------------------------------------------
-- user_roles
--
-- A collection table rather than a roles table plus a join table. Roles are a
-- small, static vocabulary (USER, MERCHANT, FRAUD_ANALYST, ADMIN, SERVICE); a separate
-- roles entity would add a migration and a join for every role change with no
-- benefit.
-- -----------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(32) NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    -- ON DELETE CASCADE: a role grant has no meaning without its user.
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN
        ('USER', 'MERCHANT', 'FRAUD_ANALYST', 'ADMIN', 'SERVICE'))
);

CREATE INDEX idx_user_roles_user ON user_roles (user_id);
CREATE INDEX idx_user_roles_role ON user_roles (role);

-- -----------------------------------------------------------------------------
-- refresh_tokens
--
-- Only the SHA-256 hash of a token is stored. A dumped table therefore yields
-- nothing usable: the value a client presents is hashed and compared, exactly as
-- a password is. SHA-256 rather than BCrypt because the input is 256 bits of
-- cryptographic randomness, so there is no low-entropy space to brute force and
-- the lookup must be fast.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id              UUID           PRIMARY KEY,

    token_hash      VARCHAR(64)    NOT NULL,
    user_id         VARCHAR(40)    NOT NULL,

    -- Every token descended from one login shares a family. Detecting reuse
    -- revokes the whole family, which ends that session everywhere without
    -- touching the user's other devices.
    family_id       VARCHAR(40)    NOT NULL,

    state           VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',

    issued_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ    NOT NULL,
    consumed_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(120),

    user_agent      VARCHAR(256),
    ip_address      VARCHAR(64),

    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_state CHECK (state IN ('ACTIVE', 'CONSUMED', 'REVOKED')),
    CONSTRAINT ck_refresh_expiry CHECK (expires_at > issued_at),
    -- A CONSUMED row must record when. This is what reuse detection reads, so it
    -- is enforced rather than assumed.
    CONSTRAINT ck_refresh_consumed_at CHECK (state <> 'CONSUMED' OR consumed_at IS NOT NULL),
    CONSTRAINT ck_refresh_revoked_at CHECK (state <> 'REVOKED' OR revoked_at IS NOT NULL)
);

COMMENT ON TABLE  refresh_tokens IS 'Rotating refresh tokens with reuse detection. Hashes only.';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Session lineage. Revoked as a unit when a consumed token is replayed.';
COMMENT ON COLUMN refresh_tokens.state IS 'ACTIVE -> CONSUMED on rotation. Presenting a CONSUMED token is treated as theft.';

-- Rotation looks up by hash; that is served by the unique constraint.
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id, state);
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);

-- -----------------------------------------------------------------------------
-- service_clients
--
-- Machine identities for the OAuth2 client-credentials grant. Rows are reconciled
-- from environment configuration at startup rather than inserted here, so no
-- credential ever enters version control.
-- -----------------------------------------------------------------------------
CREATE TABLE service_clients (
    id                 BIGSERIAL     PRIMARY KEY,
    client_id          VARCHAR(64)   NOT NULL,
    client_secret_hash VARCHAR(120)  NOT NULL,

    -- Comma-separated: a handful of static internal clients does not justify two
    -- more tables and the joins that come with them.
    scopes             VARCHAR(256)  NOT NULL DEFAULT 'internal',
    roles              VARCHAR(256)  NOT NULL DEFAULT 'SERVICE',

    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    description        VARCHAR(256),

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ,

    CONSTRAINT uq_service_clients_client_id UNIQUE (client_id)
);

COMMENT ON TABLE service_clients IS 'Machine identities. Secrets stored as BCrypt hashes, seeded from the environment.';
COMMENT ON COLUMN service_clients.last_used_at IS 'Makes a dormant, forgotten credential easy to spot and retire.';

-- -----------------------------------------------------------------------------
-- audit_logs
--
-- Append-only security trail. Grants are restricted in V2 so the application
-- role cannot UPDATE or DELETE here: an audit trail that the application can
-- rewrite after being compromised is not evidence of anything.
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id             UUID          PRIMARY KEY,

    event_type     VARCHAR(48)   NOT NULL,
    outcome        VARCHAR(16)   NOT NULL,

    -- User ID, client ID, or 'anonymous' when a failed login could not be
    -- attributed to a known account.
    actor_id       VARCHAR(64),
    target_id      VARCHAR(64),

    ip_address     VARCHAR(64),
    user_agent     VARCHAR(256),
    correlation_id VARCHAR(64),

    description    VARCHAR(512),
    -- Non-sensitive structured context. Emails here are masked (as***@host).
    metadata       TEXT,

    occurred_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'SUSPICIOUS'))
);

COMMENT ON TABLE  audit_logs IS 'Append-only security audit trail. Never contains credentials.';
COMMENT ON COLUMN audit_logs.correlation_id IS 'Links an audit entry to the full distributed trace of the request.';

CREATE INDEX idx_audit_actor    ON audit_logs (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_event    ON audit_logs (event_type, occurred_at DESC);
CREATE INDEX idx_audit_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_outcome  ON audit_logs (outcome, occurred_at DESC);

-- Supports the credential-stuffing query: failed logins from one IP in a window.
-- Partial, because only LOGIN_FAILED rows are ever counted this way, which keeps
-- the index small next to the full audit volume.
CREATE INDEX idx_audit_failed_login_ip ON audit_logs (ip_address, occurred_at DESC)
    WHERE event_type = 'LOGIN_FAILED';
