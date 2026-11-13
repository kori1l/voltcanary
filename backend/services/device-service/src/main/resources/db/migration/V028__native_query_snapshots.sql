CREATE TABLE cfg_native_query_snapshot (
    snapshot_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    schema_revision BIGINT NOT NULL,
    generation_id UUID NOT NULL,
    data_version BIGINT NOT NULL,
    schema_digest VARCHAR(64) NOT NULL,
    scope_digest VARCHAR(64) NOT NULL,
    query_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    canonical_rows TEXT NOT NULL,
    asof_projection_digest VARCHAR(64)
);
CREATE INDEX idx_native_query_snapshot_expiry ON cfg_native_query_snapshot(account_id, expires_at);

CREATE TABLE cfg_native_snapshot_interval (
    snapshot_id UUID NOT NULL REFERENCES cfg_native_query_snapshot(snapshot_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 255),
    key_digest VARCHAR(64) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE,
    ends_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY(snapshot_id, ordinal),
    CHECK (ends_at IS NULL OR (starts_at IS NOT NULL AND ends_at >= starts_at))
);
CREATE INDEX idx_native_snapshot_asof ON cfg_native_snapshot_interval(snapshot_id, key_digest, starts_at);
