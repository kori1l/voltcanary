ALTER TABLE cfg_source_coverage_attestation ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE cfg_source_coverage_attestation ADD CONSTRAINT ck_source_coverage_owner_time
    CHECK (accepted_at IS NULL OR accepted_at >= covered_until);
