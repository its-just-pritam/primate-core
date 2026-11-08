--liquibase formatted sql

--changeset platito:007-drop-onboarding-step-check
-- The 0-5 range for onboarding_step is enforced by the API instead of the database.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS ck_app_user_onboarding_step;
--rollback ALTER TABLE app_user ADD CONSTRAINT ck_app_user_onboarding_step CHECK (onboarding_step BETWEEN 0 AND 5);
