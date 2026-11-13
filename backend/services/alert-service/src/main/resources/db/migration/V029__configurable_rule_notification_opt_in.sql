ALTER TABLE notification_preferences
    ADD COLUMN configurable_rule_alerts_enabled BOOLEAN NOT NULL DEFAULT FALSE;
