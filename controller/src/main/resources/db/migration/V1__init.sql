-- Devices: one row per serial number in the provisioning file.
-- Lifecycle: ALLOWED -> ONBOARDED -> (DECOMMISSIONED)
CREATE TABLE devices (
    serial              TEXT PRIMARY KEY,
    model               TEXT NOT NULL,
    device_group        TEXT NOT NULL,
    hostname            TEXT NOT NULL,
    system_ip           INET NOT NULL UNIQUE,
    site_id             BIGINT NOT NULL,
    region              TEXT NOT NULL,
    variables           JSONB NOT NULL DEFAULT '{}'::jsonb,   -- per-device template variables
    state               TEXT NOT NULL DEFAULT 'ALLOWED',
    token_hash          TEXT,                                  -- SHA-256 of the one-time bootstrap token
    token_expires_at    TIMESTAMPTZ,
    cert_serial         TEXT,                                  -- current identity certificate
    cert_not_after      TIMESTAMPTZ,
    onboarded_at        TIMESTAMPTZ,
    -- config state
    intended_version    BIGINT NOT NULL DEFAULT 0,
    intended_hash       TEXT,
    applied_version     BIGINT NOT NULL DEFAULT 0,
    running_hash        TEXT,
    healthy             BOOLEAN,
    drifted_since       TIMESTAMPTZ,
    -- session state
    connected           BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT devices_state_chk CHECK (state IN ('ALLOWED', 'ONBOARDED', 'DECOMMISSIONED'))
);
CREATE INDEX devices_group_idx ON devices (device_group);

-- Config templates: immutable versions.
CREATE TABLE templates (
    name        TEXT NOT NULL,
    version     INT NOT NULL,
    body        JSONB NOT NULL,
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (name, version)
);

-- Every config ever intended for a device, by device version. Version numbers are
-- strictly increasing per device; a rollback is a new version carrying old content.
CREATE TABLE device_configs (
    serial            TEXT NOT NULL REFERENCES devices (serial),
    version           BIGINT NOT NULL,
    template_name     TEXT,
    template_version  INT,
    config            JSONB NOT NULL,
    config_hash       TEXT NOT NULL,
    reason            TEXT NOT NULL,      -- deployment, rollback, drift-remediation, day0
    deployment_id     UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (serial, version)
);

-- Push commands, delivered to whichever controller instance holds the device's session.
CREATE TABLE commands (
    command_id      UUID PRIMARY KEY,
    serial          TEXT NOT NULL REFERENCES devices (serial),
    version         BIGINT NOT NULL,
    state           TEXT NOT NULL DEFAULT 'QUEUED',
    result          TEXT,
    detail          TEXT,
    deployment_id   UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT commands_state_chk CHECK (state IN ('QUEUED', 'SENT', 'DONE', 'SUPERSEDED'))
);
CREATE INDEX commands_queued_idx ON commands (serial) WHERE state = 'QUEUED';

-- Staged rollouts of a template version to a device group.
CREATE TABLE deployments (
    id                  UUID PRIMARY KEY,
    template_name       TEXT NOT NULL,
    template_version    INT NOT NULL,
    device_group        TEXT NOT NULL,
    batch_percents      INT[] NOT NULL,       -- cumulative, e.g. {5,25,100}
    max_failure_pct     INT NOT NULL,
    health_timeout_sec  INT NOT NULL,
    state               TEXT NOT NULL,
    current_batch       INT NOT NULL DEFAULT 0,
    batch_started_at    TIMESTAMPTZ,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT deployments_state_chk CHECK (state IN
        ('RUNNING', 'PAUSED', 'COMPLETED', 'ROLLING_BACK', 'ROLLED_BACK', 'CANCELLED'))
);

CREATE TABLE deployment_targets (
    deployment_id   UUID NOT NULL REFERENCES deployments (id),
    serial          TEXT NOT NULL REFERENCES devices (serial),
    batch           INT NOT NULL,
    state           TEXT NOT NULL DEFAULT 'PENDING',
    from_version    BIGINT,
    to_version      BIGINT,
    command_id      UUID,
    rollback_command_id UUID,
    detail          TEXT,
    pushed_at       TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    PRIMARY KEY (deployment_id, serial),
    CONSTRAINT targets_state_chk CHECK (state IN
        ('PENDING', 'PUSHED', 'SUCCEEDED', 'FAILED', 'DEFERRED', 'ROLLED_BACK'))
);
CREATE INDEX targets_batch_idx ON deployment_targets (deployment_id, batch, state);

-- Append-only event log: onboarding, pushes, results, drift, rollouts. Read by
-- operators and by diagnostics tools that correlate config changes with incidents.
CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    serial      TEXT,
    deployment_id UUID,
    kind        TEXT NOT NULL,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX events_serial_idx ON events (serial, id);

-- The config a device group should run: day-0 config for newly onboarded devices,
-- and updated when a rollout to the group completes.
CREATE TABLE group_baselines (
    device_group      TEXT PRIMARY KEY,
    template_name     TEXT NOT NULL,
    template_version  INT NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (template_name, template_version) REFERENCES templates (name, version)
);
