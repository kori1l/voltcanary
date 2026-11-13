CREATE TABLE vcl_journal_window_cursor (
    account_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    input_generation UUID NOT NULL,
    checkpoint BIGINT NOT NULL CHECK (checkpoint >= 0),
    PRIMARY KEY (account_id,deployment_epoch),
    FOREIGN KEY (account_id,deployment_epoch)
        REFERENCES vcl_rule_deployments(account_id,deployment_epoch)
);
