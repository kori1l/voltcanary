CREATE TABLE cfg_source_field_identity (
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    field_key VARCHAR(64) NOT NULL,
    field_id UUID NOT NULL UNIQUE,
    PRIMARY KEY(source_id, field_key)
);

CREATE TABLE cfg_source_binding_snapshot (
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    schema_revision BIGINT NOT NULL CHECK (schema_revision > 0),
    snapshot_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL REFERENCES accounts(id),
    device_id UUID NOT NULL REFERENCES devices(id),
    definition_digest VARCHAR(64) NOT NULL,
    canonical_definition TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(source_id, schema_revision)
);

CREATE INDEX idx_cfg_source_binding_account ON cfg_source_binding_snapshot(account_id, source_id, schema_revision);
