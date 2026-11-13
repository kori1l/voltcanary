ALTER TABLE vcl_rule_deployments ADD COLUMN drain_before TIMESTAMP WITH TIME ZONE;
ALTER TABLE vcl_rule_deployments ADD COLUMN starts_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE vcl_rule_deployments ADD COLUMN start_checkpoint_digest VARCHAR(64);
ALTER TABLE vcl_rule_deployments ADD COLUMN predecessor_epoch UUID;

ALTER TABLE vcl_rule_deployments ADD CONSTRAINT ck_vcl_deployment_drain
    CHECK (drain_before IS NULL OR state = 'STAGED');
ALTER TABLE vcl_rule_deployments ADD CONSTRAINT ck_vcl_deployment_start
    CHECK ((starts_at IS NULL AND start_checkpoint_digest IS NULL AND predecessor_epoch IS NULL)
        OR (starts_at IS NOT NULL AND start_checkpoint_digest IS NOT NULL
            AND LENGTH(start_checkpoint_digest) = 64 AND predecessor_epoch IS NOT NULL
            AND predecessor_epoch <> deployment_epoch));
