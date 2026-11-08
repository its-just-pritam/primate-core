--liquibase formatted sql

--changeset platito:010-sheets-sync-run
-- One row per nightly Google Sheets sync. counts holds inserted/updated/unchanged/skipped per table.
CREATE TABLE sheets_sync_run (
    id          BIGSERIAL PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status      TEXT CHECK (status IN ('SUCCESS', 'FAILED')),
    counts      JSONB,
    warnings    JSONB,
    error       TEXT
);
--rollback DROP TABLE sheets_sync_run;
