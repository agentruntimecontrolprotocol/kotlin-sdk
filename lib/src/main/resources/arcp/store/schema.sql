-- ARCP event log schema (RFC §6.4, §19).
--
-- The event log is append-only and globally ordered by `seq`. Per-message
-- idempotency is enforced by the unique (session_id, message_id) constraint.
-- Logical idempotency keys (RFC §6.4) are stored alongside outcomes so a
-- repeated logical command returns the prior outcome rather than re-executing.

CREATE TABLE IF NOT EXISTS arcp_envelope (
    seq            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT,
    message_id     TEXT NOT NULL,
    type           TEXT NOT NULL,
    timestamp_iso  TEXT NOT NULL,
    job_id         TEXT,
    stream_id      TEXT,
    subscription_id TEXT,
    trace_id       TEXT,
    correlation_id TEXT,
    causation_id   TEXT,
    priority       TEXT,
    body_json      TEXT NOT NULL,
    UNIQUE (session_id, message_id)
);

CREATE INDEX IF NOT EXISTS arcp_envelope_session_seq
    ON arcp_envelope (session_id, seq);

CREATE INDEX IF NOT EXISTS arcp_envelope_job_seq
    ON arcp_envelope (job_id, seq);

CREATE INDEX IF NOT EXISTS arcp_envelope_stream_seq
    ON arcp_envelope (stream_id, seq);

CREATE TABLE IF NOT EXISTS arcp_idempotency (
    principal       TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    outcome_json    TEXT NOT NULL,
    created_at_iso  TEXT NOT NULL,
    expires_at_iso  TEXT NOT NULL,
    PRIMARY KEY (principal, idempotency_key)
);

CREATE INDEX IF NOT EXISTS arcp_idempotency_expiry
    ON arcp_idempotency (expires_at_iso);

CREATE TABLE IF NOT EXISTS arcp_artifact (
    artifact_id     TEXT PRIMARY KEY,
    session_id      TEXT,
    media_type      TEXT NOT NULL,
    size_bytes      INTEGER NOT NULL,
    sha256          TEXT,
    expires_at_iso  TEXT,
    body_blob       BLOB NOT NULL
);

CREATE INDEX IF NOT EXISTS arcp_artifact_session
    ON arcp_artifact (session_id);

CREATE INDEX IF NOT EXISTS arcp_artifact_expiry
    ON arcp_artifact (expires_at_iso);
