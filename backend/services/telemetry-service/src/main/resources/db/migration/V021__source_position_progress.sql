ALTER TABLE cfg_observation ADD COLUMN producer_epoch UUID;
ALTER TABLE cfg_observation ADD COLUMN producer_sequence BIGINT;
CREATE UNIQUE INDEX idx_observation_producer_position
    ON cfg_observation(binding_id,input_generation,snapshot_id,producer_epoch,producer_sequence);

CREATE TABLE cfg_source_position_range (
    binding_id UUID NOT NULL REFERENCES cfg_observation_stream(binding_id) ON DELETE CASCADE,
    input_generation UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    producer_epoch UUID NOT NULL,
    first_sequence BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    PRIMARY KEY (binding_id,input_generation,snapshot_id,producer_epoch,first_sequence),
    UNIQUE (binding_id,input_generation,snapshot_id,producer_epoch,last_sequence),
    CHECK (first_sequence >= 1 AND last_sequence >= first_sequence)
);
