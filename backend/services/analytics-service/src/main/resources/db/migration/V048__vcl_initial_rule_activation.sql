ALTER TABLE vcl_rule_deployments DROP CONSTRAINT ck_vcl_deployment_start;
ALTER TABLE vcl_rule_deployments ADD CONSTRAINT ck_vcl_deployment_start
    CHECK ((starts_at IS NULL AND start_checkpoint_digest IS NULL AND predecessor_epoch IS NULL)
        OR (starts_at IS NOT NULL AND start_checkpoint_digest IS NOT NULL
            AND LENGTH(start_checkpoint_digest) = 64
            AND (predecessor_epoch IS NULL OR predecessor_epoch <> deployment_epoch)));
