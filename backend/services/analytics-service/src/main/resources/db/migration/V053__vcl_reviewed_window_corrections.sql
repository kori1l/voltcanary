ALTER TABLE vcl_rule_deployments ADD COLUMN reference_correction_revision BIGINT NOT NULL DEFAULT 0
    CHECK (reference_correction_revision >= 0);

CREATE TABLE vcl_reviewed_window_corrections (
    account_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    correction_id UUID NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    original_measure_id UUID NOT NULL,
    confirmation_digest VARCHAR(64) NOT NULL,
    canonical_payload TEXT NOT NULL,
    alert_proposal_id UUID,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, deployment_epoch, correction_id),
    FOREIGN KEY (account_id, deployment_epoch) REFERENCES vcl_rule_deployments(account_id, deployment_epoch),
    FOREIGN KEY (account_id, key_digest, original_measure_id) REFERENCES vcl_keyed_outbox(account_id, key_digest, output_id),
    FOREIGN KEY (account_id, key_digest, alert_proposal_id) REFERENCES vcl_keyed_outbox(account_id, key_digest, output_id),
    CHECK (LENGTH(canonical_payload) <= 65536),
    CHECK (window_start < window_end)
);
CREATE INDEX idx_vcl_reviewed_correction_range
    ON vcl_reviewed_window_corrections(account_id, deployment_epoch, window_start, window_end);
