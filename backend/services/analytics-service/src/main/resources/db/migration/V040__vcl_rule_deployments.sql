CREATE TABLE vcl_rule_deployments (
    account_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    bundle_id UUID NOT NULL,
    bundle_digest VARCHAR(64) NOT NULL,
    grant_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    active_slot INTEGER,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, deployment_epoch),
    FOREIGN KEY (account_id, bundle_id) REFERENCES vcl_compilation_bundles(account_id, bundle_id),
    CHECK (LENGTH(bundle_digest) = 64),
    CHECK (state IN ('STAGED', 'ACTIVE', 'REVOKED')),
    CHECK ((state = 'ACTIVE' AND active_slot = 1) OR (state <> 'ACTIVE' AND active_slot IS NULL))
);

CREATE UNIQUE INDEX ux_vcl_rule_active_deployment
    ON vcl_rule_deployments(account_id, rule_id, active_slot);

CREATE INDEX ix_vcl_rule_deployment_scan
    ON vcl_rule_deployments(state, account_id, rule_id);
