--liquibase formatted sql

--changeset platito:001-app-user
CREATE TABLE app_user (
    id                BIGSERIAL PRIMARY KEY,
    email             TEXT             NOT NULL,
    name              TEXT             NOT NULL,
    first_name        TEXT             NOT NULL DEFAULT '',
    last_name         TEXT             NOT NULL DEFAULT '',
    -- Salted Node-compatible scrypt hash: "<salt hex>:<digest hex>".
    password_hash     TEXT,
    weight_kg         DOUBLE PRECISION NOT NULL DEFAULT 0,
    height_cm         DOUBLE PRECISION NOT NULL DEFAULT 0,
    workouts_per_week DOUBLE PRECISION NOT NULL DEFAULT 0,
    onboarding_step   SMALLINT         NOT NULL DEFAULT 0,
    -- IANA zone reported by the user's browser; Garmin days are resolved in it.
    time_zone         TEXT             NOT NULL DEFAULT 'UTC',
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));
CREATE UNIQUE INDEX ux_app_user_name ON app_user (lower(name));
--rollback DROP TABLE app_user;
