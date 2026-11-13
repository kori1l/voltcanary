UPDATE vcl_rule_deployments
SET state='REVOKED', active_slot=NULL, revision=revision+1, changed_at=CURRENT_TIMESTAMP
WHERE actor_id IS NULL AND state <> 'REVOKED';

ALTER TABLE vcl_rule_deployments
    ADD CONSTRAINT ck_vcl_deployment_actor_active
    CHECK (actor_id IS NOT NULL OR state = 'REVOKED');
