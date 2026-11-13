CREATE TABLE vcl_rule_evaluator_readiness (
    account_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    bundle_digest VARCHAR(64) NOT NULL,
    checkpoint_digest VARCHAR(64) NOT NULL,
    readiness_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY (account_id, deployment_epoch),
    FOREIGN KEY (account_id, deployment_epoch)
        REFERENCES vcl_rule_deployments(account_id, deployment_epoch),
    CHECK (LENGTH(bundle_digest) = 64),
    CHECK (LENGTH(checkpoint_digest) = 64),
    CHECK (LENGTH(readiness_digest) = 64)
);
