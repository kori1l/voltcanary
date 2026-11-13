ALTER TABLE cfg_source_binding_snapshot ADD COLUMN canonical_normalization TEXT;
ALTER TABLE cfg_source_binding_snapshot ADD COLUMN normalization_digest VARCHAR(64);
