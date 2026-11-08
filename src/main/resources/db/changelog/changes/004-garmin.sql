--liquibase formatted sql

--changeset platito:004-garmin-integration
CREATE TABLE garmin_integration (
    user_id               BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    -- AES-GCM encrypted Garmin Personal API key.
    api_key_ciphertext    TEXT        NOT NULL,
    device_name           TEXT,
    connected_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sync_started_at  TIMESTAMPTZ,
    last_sync_finished_at TIMESTAMPTZ,
    last_sync_status      TEXT CHECK (last_sync_status IN ('SUCCESS', 'FAILED')),
    last_sync_error       TEXT
);
--rollback DROP TABLE garmin_integration;

--changeset platito:004-garmin-daily-metric
-- Raw Garmin payload per user, kind and calendar day (in the user's time zone).
CREATE TABLE garmin_daily_metric (
    user_id       BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL CHECK (kind IN ('DAILY_SUMMARY', 'SLEEP')),
    calendar_date DATE        NOT NULL,
    payload       JSONB       NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, kind, calendar_date)
);
--rollback DROP TABLE garmin_daily_metric;
