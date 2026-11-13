CREATE TABLE vcl_journal_watermarks (
    account_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    scope_digest VARCHAR(64) NOT NULL,
    covered_from TIMESTAMP WITH TIME ZONE NOT NULL,
    covered_until TIMESTAMP WITH TIME ZONE NOT NULL,
    watermark TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY (account_id, deployment_epoch, scope_digest),
    FOREIGN KEY (account_id, deployment_epoch)
        REFERENCES vcl_rule_deployments(account_id, deployment_epoch),
    CHECK (covered_until > covered_from),
    CHECK (watermark <= covered_until)
);
