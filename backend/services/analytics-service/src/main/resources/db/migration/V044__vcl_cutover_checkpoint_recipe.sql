CREATE TABLE vcl_rule_start_checkpoint (
    account_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    recipe_digest VARCHAR(64) NOT NULL,
    recipe_payload TEXT NOT NULL,
    PRIMARY KEY (account_id, deployment_epoch),
    FOREIGN KEY (account_id, deployment_epoch)
        REFERENCES vcl_rule_deployments(account_id, deployment_epoch),
    CHECK (LENGTH(recipe_digest) = 64),
    CHECK (LENGTH(recipe_payload) <= 1048576)
);
