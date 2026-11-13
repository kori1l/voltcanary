CREATE TABLE cfg_rule_alert_private_evidence (
    account_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    policy_id UUID NOT NULL REFERENCES cfg_evidence_policy(id),
    binding_id UUID NOT NULL,
    schema_revision BIGINT NOT NULL CHECK (schema_revision > 0),
    snapshot_id UUID NOT NULL,
    input_generation UUID NOT NULL,
    coverage_proof_id UUID NOT NULL,
    coverage_digest VARCHAR(64) NOT NULL CHECK (LENGTH(coverage_digest) = 64),
    measure_id UUID NOT NULL,
    measure_digest VARCHAR(64) NOT NULL CHECK (LENGTH(measure_digest) = 64),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, proposal_id),
    FOREIGN KEY (account_id, proposal_id) REFERENCES cfg_rule_alert_effect_receipt(account_id, proposal_id) ON DELETE CASCADE
);

CREATE INDEX ix_cfg_rule_alert_private_evidence_expiry
    ON cfg_rule_alert_private_evidence(expires_at, account_id, proposal_id);
