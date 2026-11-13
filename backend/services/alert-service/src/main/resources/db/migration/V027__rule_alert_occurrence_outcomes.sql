CREATE TABLE cfg_rule_alert_occurrence_outcome (
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    deduplication_key VARCHAR(500) NOT NULL,
    alert_id UUID,
    PRIMARY KEY (account_id, deduplication_key)
);

INSERT INTO cfg_rule_alert_occurrence_outcome(account_id,deduplication_key,alert_id)
SELECT account_id,deduplication_key,id FROM alerts
WHERE alert_type='CONFIGURABLE_RULE' AND deduplication_key LIKE 'vcl:%' AND account_id IS NOT NULL;

ALTER TABLE cfg_rule_alert_effect_receipt ALTER COLUMN alert_id DROP NOT NULL;
ALTER TABLE cfg_rule_alert_effect_receipt ADD COLUMN original_alert_id UUID;
UPDATE cfg_rule_alert_effect_receipt SET original_alert_id=alert_id, alert_id=NULL;

ALTER TABLE cfg_rule_alert_private_evidence ADD COLUMN device_id UUID;
UPDATE cfg_rule_alert_private_evidence e SET device_id=(
    SELECT a.device_id FROM cfg_rule_alert_effect_receipt r JOIN alerts a ON a.id=r.original_alert_id
    WHERE r.account_id=e.account_id AND r.proposal_id=e.proposal_id
);
ALTER TABLE cfg_rule_alert_private_evidence ALTER COLUMN device_id SET NOT NULL;
CREATE INDEX ix_cfg_rule_alert_private_evidence_device ON cfg_rule_alert_private_evidence(device_id);
