CREATE TABLE vcl_compilation_bundles (
    account_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    bundle_version VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    canonical_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id, bundle_id),
    CHECK (LENGTH(payload_digest) = 64),
    CHECK (LENGTH(canonical_payload) <= 8388608)
);
