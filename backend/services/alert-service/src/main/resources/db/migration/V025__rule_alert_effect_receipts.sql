CREATE TABLE cfg_rule_alert_effect_receipt (
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    proposal_id UUID NOT NULL,
    proposal_digest VARCHAR(64) NOT NULL,
    alert_id UUID NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    receipt_id UUID NOT NULL UNIQUE,
    PRIMARY KEY (account_id, proposal_id)
);
