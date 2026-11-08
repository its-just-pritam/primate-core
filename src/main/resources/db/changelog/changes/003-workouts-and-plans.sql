--liquibase formatted sql

--changeset platito:003-workout
CREATE TABLE workout (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT           NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name           TEXT             NOT NULL,
    calories_burnt DOUBLE PRECISION NOT NULL CHECK (calories_burnt >= 0),
    performed_at   TIMESTAMPTZ      NOT NULL,
    source         TEXT             NOT NULL CHECK (source IN ('DIRECT', 'GARMIN')),
    -- Garmin activityId for GARMIN rows; lets sync upsert instead of matching on values.
    external_id    TEXT
);
CREATE INDEX ix_workout_user_performed_at ON workout (user_id, performed_at);
CREATE UNIQUE INDEX ux_workout_user_external ON workout (user_id, source, external_id) WHERE external_id IS NOT NULL;
--rollback DROP TABLE workout;

--changeset platito:003-plan
-- status is derived: DELETED when deleted_at is set, otherwise ACTIVE once start_at has passed.
CREATE TABLE plan (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT           NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name             TEXT             NOT NULL,
    target_weight_kg DOUBLE PRECISION NOT NULL CHECK (target_weight_kg > 0),
    resting_calories DOUBLE PRECISION NOT NULL CHECK (resting_calories > 0),
    start_at         TIMESTAMPTZ      NOT NULL,
    comment          TEXT             NOT NULL DEFAULT '',
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_plan_user ON plan (user_id);
--rollback DROP TABLE plan;
