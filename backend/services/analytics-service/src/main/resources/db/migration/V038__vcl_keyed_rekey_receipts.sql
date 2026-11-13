CREATE TABLE vcl_keyed_rekey_receipts (
    account_id UUID NOT NULL,
    source_key_digest VARCHAR(64) NOT NULL,
    output_id UUID NOT NULL,
    target_key_digest VARCHAR(64) NOT NULL,
    pins_digest VARCHAR(64) NOT NULL,
    output_revision BIGINT NOT NULL,
    output_digest VARCHAR(64) NOT NULL,
    transition_digest VARCHAR(64) NOT NULL,
    committed_revision BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    receipt_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY (account_id,source_key_digest,output_id),
    FOREIGN KEY (account_id,source_key_digest) REFERENCES vcl_keyed_state(account_id,key_digest),
    FOREIGN KEY (account_id,target_key_digest) REFERENCES vcl_keyed_state(account_id,key_digest),
    FOREIGN KEY (account_id,source_key_digest,output_id) REFERENCES vcl_keyed_outbox(account_id,key_digest,output_id),
    UNIQUE (receipt_id),
    CHECK (output_revision > 0 AND committed_revision > 0)
);
