CREATE TABLE vcl_keyed_state (
    account_id UUID NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    rule_id UUID NOT NULL,
    deployment_epoch UUID NOT NULL,
    subject_digest VARCHAR(64) NOT NULL,
    occurrence_digest VARCHAR(64) NOT NULL,
    state_version VARCHAR(64) NOT NULL,
    registration_digest VARCHAR(64) NOT NULL,
    pins_digest VARCHAR(64) NOT NULL,
    state_revision BIGINT NOT NULL,
    state_digest VARCHAR(64) NOT NULL,
    state_payload TEXT NOT NULL,
    inbox_count INTEGER NOT NULL,
    lease_holder UUID,
    lease_fence BIGINT NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (account_id, key_digest),
    CHECK (state_revision >= 0 AND lease_fence >= 0),
    CHECK (inbox_count >= 0 AND inbox_count <= 4096),
    CHECK (LENGTH(state_payload) <= 65536)
);

CREATE TABLE vcl_keyed_inbox (
    account_id UUID NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    input_id UUID NOT NULL,
    input_revision BIGINT NOT NULL,
    input_kind VARCHAR(16) DEFAULT 'OBSERVATION' NOT NULL,
    transition_digest VARCHAR(64) NOT NULL,
    committed_revision BIGINT NOT NULL,
    PRIMARY KEY (account_id, key_digest, input_kind, input_id, input_revision),
    FOREIGN KEY (account_id, key_digest) REFERENCES vcl_keyed_state(account_id, key_digest),
    CHECK (input_revision > 0 AND committed_revision > 0),
    CHECK (input_kind IN ('OBSERVATION','TIMER'))
);
