--liquibase formatted sql

--changeset platito:005-shedlock
-- Keeps scheduled jobs from running on more than one backend instance at a time.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
--rollback DROP TABLE shedlock;
