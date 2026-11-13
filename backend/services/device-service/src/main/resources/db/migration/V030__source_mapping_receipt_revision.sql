ALTER TABLE cfg_source_mapping_receipt ADD COLUMN review_scope_digest VARCHAR(64);
CREATE UNIQUE INDEX idx_cfg_source_mapping_field_revision
    ON cfg_source_mapping_receipt(source_id, field_id, applied_revision);
