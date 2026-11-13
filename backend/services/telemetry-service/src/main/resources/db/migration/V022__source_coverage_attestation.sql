CREATE TABLE cfg_source_coverage_attestation (
    proof_id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES cfg_observation_stream(binding_id) ON DELETE CASCADE,
    input_generation UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    producer_epoch UUID NOT NULL,
    through_sequence BIGINT NOT NULL CHECK (through_sequence > 0),
    covered_from TIMESTAMP WITH TIME ZONE NOT NULL,
    covered_until TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (covered_until > covered_from)
);
CREATE INDEX idx_source_coverage_attestation_binding ON cfg_source_coverage_attestation(binding_id);
