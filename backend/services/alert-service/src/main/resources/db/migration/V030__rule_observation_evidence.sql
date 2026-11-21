ALTER TABLE cfg_rule_alert_private_evidence
    ADD COLUMN observation_references VARCHAR(4096) DEFAULT '[]' NOT NULL;
