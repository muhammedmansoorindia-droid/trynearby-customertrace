-- V3 — allow audit logging of invalid scans.
--
-- `scan_events.campaign_id` was originally NOT NULL, which made it impossible to record a
-- rejected scan (an unrecognised code has no campaign). Failed attempts are exactly the ones
-- worth auditing, so the column becomes nullable. The existing foreign key is preserved by the
-- MODIFY, so no constraint is dropped or recreated.
--
-- Additive and non-destructive: existing rows are unaffected.

ALTER TABLE scan_events
    MODIFY COLUMN campaign_id BIGINT NULL;