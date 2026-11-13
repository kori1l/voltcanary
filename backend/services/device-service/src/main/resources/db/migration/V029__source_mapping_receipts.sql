CREATE TABLE cfg_source_mapping_receipt (
    source_id UUID NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    field_id UUID NOT NULL,
    base_revision BIGINT NOT NULL,
    applied_revision BIGINT NOT NULL,
    snapshot_id UUID NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    canonical_conversion TEXT NOT NULL,
    PRIMARY KEY (source_id, review_digest)
);
