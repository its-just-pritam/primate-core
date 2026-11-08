--liquibase formatted sql

--changeset platito:009-user-phone-number
-- Optional; existing users have none.
ALTER TABLE app_user ADD COLUMN phone_number TEXT;
--rollback ALTER TABLE app_user DROP COLUMN phone_number;
