ALTER TABLE vcl_keyed_inbox ADD COLUMN input_digest VARCHAR(64);
ALTER TABLE vcl_keyed_inbox ADD COLUMN receipt_digest VARCHAR(64);
ALTER TABLE vcl_keyed_inbox ADD COLUMN outputs_digest VARCHAR(64);
ALTER TABLE vcl_keyed_inbox ADD CONSTRAINT chk_vcl_input_receipt_digests
    CHECK ((input_digest IS NULL AND receipt_digest IS NULL AND outputs_digest IS NULL)
        OR (input_digest IS NOT NULL AND receipt_digest IS NOT NULL AND outputs_digest IS NOT NULL));
