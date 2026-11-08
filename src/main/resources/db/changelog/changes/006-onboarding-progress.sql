--liquibase formatted sql

--changeset platito:006-onboarding-progress
-- onboarding_step now counts completed onboarding steps (0-5) instead of a 0/1 finished flag.
-- Before this change no user could have partial progress, so 1 always meant finished.
UPDATE app_user SET onboarding_step = 5 WHERE onboarding_step = 1;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_onboarding_step CHECK (onboarding_step BETWEEN 0 AND 5);
--rollback ALTER TABLE app_user DROP CONSTRAINT ck_app_user_onboarding_step; UPDATE app_user SET onboarding_step = CASE WHEN onboarding_step = 5 THEN 1 ELSE 0 END;
