CREATE TABLE cfg_observation_stream (
    binding_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    head_checkpoint BIGINT NOT NULL DEFAULT 0 CHECK(head_checkpoint >= 0)
);
CREATE INDEX idx_observation_stream_device ON cfg_observation_stream(device_id);
CREATE TABLE cfg_observation_schema (
    binding_id UUID NOT NULL,
    schema_revision BIGINT NOT NULL CHECK(schema_revision > 0),
    snapshot_id UUID NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    external_source_id VARCHAR(512) NOT NULL,
    canonical_definition TEXT NOT NULL,
    PRIMARY KEY(binding_id,schema_revision),
    FOREIGN KEY(binding_id) REFERENCES cfg_observation_stream(binding_id)
);
CREATE TABLE cfg_observation_input (
    event_id UUID PRIMARY KEY,
    binding_id UUID NOT NULL,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','ACCEPTED')),
    payload_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_observation_pending ON cfg_observation_input(status,retry_after,event_id);
CREATE INDEX idx_observation_input_device ON cfg_observation_input(device_id);
CREATE TABLE cfg_observation (
    event_id UUID PRIMARY KEY REFERENCES cfg_observation_input(event_id),
    binding_id UUID NOT NULL REFERENCES cfg_observation_stream(binding_id),
    checkpoint BIGINT NOT NULL CHECK(checkpoint > 0),
    schema_revision BIGINT NOT NULL,
    snapshot_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_observation TEXT NOT NULL,
    UNIQUE(binding_id,checkpoint)
);
CREATE INDEX idx_observation_time ON cfg_observation(binding_id,occurred_at,checkpoint);
