ALTER TABLE cfg_observation_stream ADD COLUMN input_generation UUID;
ALTER TABLE cfg_observation_stream ADD COLUMN generation_start_checkpoint BIGINT;
ALTER TABLE cfg_observation_stream ADD COLUMN generation_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cfg_observation_stream ADD CONSTRAINT uq_observation_generation UNIQUE(input_generation);
ALTER TABLE cfg_observation_stream ADD CONSTRAINT uq_observation_binding_generation UNIQUE(binding_id,input_generation);
ALTER TABLE cfg_observation_stream ADD CONSTRAINT ck_observation_generation CHECK (
    (input_generation IS NULL AND generation_start_checkpoint IS NULL AND generation_started_at IS NULL)
    OR (input_generation IS NOT NULL AND generation_start_checkpoint IS NOT NULL AND generation_started_at IS NOT NULL
        AND generation_start_checkpoint >= 0 AND generation_start_checkpoint <= head_checkpoint)
);
ALTER TABLE cfg_observation ADD COLUMN input_generation UUID;
ALTER TABLE cfg_observation ADD CONSTRAINT fk_observation_generation FOREIGN KEY(binding_id,input_generation)
    REFERENCES cfg_observation_stream(binding_id,input_generation);
CREATE INDEX idx_observation_generation_checkpoint ON cfg_observation(binding_id,input_generation,checkpoint);
CREATE INDEX idx_observation_binding_pending ON cfg_observation_input(binding_id,status);
