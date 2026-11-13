CREATE TABLE cfg_source_coverage_composite (
    proof_id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES cfg_observation_stream(binding_id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL,
    input_generation UUID NOT NULL,
    covered_from TIMESTAMP WITH TIME ZONE NOT NULL,
    covered_until TIMESTAMP WITH TIME ZONE NOT NULL,
    part_ids TEXT NOT NULL CHECK (LENGTH(part_ids) <= 2368),
    CHECK (covered_until > covered_from)
);
CREATE INDEX ix_source_coverage_composite_binding ON cfg_source_coverage_composite(binding_id);
