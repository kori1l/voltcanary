ALTER TABLE vcl_keyed_state ADD COLUMN outbox_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE vcl_keyed_state ADD CONSTRAINT chk_vcl_outbox_budget CHECK (outbox_count >= 0 AND outbox_count <= 4096);
CREATE TABLE vcl_keyed_outbox (
    account_id UUID NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    output_id UUID NOT NULL,
    output_kind VARCHAR(32) NOT NULL,
    output_digest VARCHAR(64) NOT NULL,
    state_revision BIGINT NOT NULL,
    target_account_id UUID,
    target_rule_id UUID,
    target_epoch UUID,
    target_subject_digest VARCHAR(64),
    target_occurrence_digest VARCHAR(64),
    payload_digest VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    ack_receipt_id UUID,
    ack_receipt_digest VARCHAR(64),
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (account_id,key_digest,output_id),
    FOREIGN KEY (account_id,key_digest) REFERENCES vcl_keyed_state(account_id,key_digest),
    CHECK (state_revision > 0),
    CHECK (output_kind IN ('MEASURE','REKEY','ALERT_PROPOSAL')),
    CHECK (LENGTH(payload) <= 65536),
    CHECK ((ack_receipt_id IS NULL AND ack_receipt_digest IS NULL AND acknowledged_at IS NULL)
        OR (ack_receipt_id IS NOT NULL AND ack_receipt_digest IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CHECK ((output_kind='REKEY' AND target_account_id IS NOT NULL AND target_rule_id IS NOT NULL AND target_epoch IS NOT NULL
        AND target_subject_digest IS NOT NULL AND target_occurrence_digest IS NOT NULL AND target_account_id=account_id)
        OR (output_kind<>'REKEY' AND target_account_id IS NULL AND target_rule_id IS NULL AND target_epoch IS NULL
        AND target_subject_digest IS NULL AND target_occurrence_digest IS NULL))
);
CREATE INDEX idx_vcl_keyed_outbox_pending ON vcl_keyed_outbox(account_id,key_digest,acknowledged_at,state_revision,output_id);
