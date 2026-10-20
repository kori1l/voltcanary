CREATE TABLE processed_variable_events (
    event_id UUID PRIMARY KEY,
    data_source_id UUID NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE variable_measurement_lineage (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    data_source_id UUID NOT NULL,
    external_source_id VARCHAR(255) NOT NULL,
    variable_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    bundle_generation BIGINT NOT NULL,
    bundle_digest VARCHAR(64) NOT NULL,
    evaluator_version VARCHAR(16) NOT NULL,
    metric_token VARCHAR(100) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    input_snapshot_json TEXT NOT NULL,
    status VARCHAR(100) NOT NULL,
    eligibility VARCHAR(16) NOT NULL,
    UNIQUE(source_event_id, variable_id, revision)
);
CREATE INDEX idx_variable_lineage_series ON variable_measurement_lineage(device_id,data_source_id,variable_id,recorded_at);
CREATE TABLE variable_series_catalog (
    data_source_id UUID NOT NULL,
    variable_id UUID NOT NULL,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    external_source_id VARCHAR(255) NOT NULL,
    variable_key VARCHAR(64) NOT NULL,
    label VARCHAR(80) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    revision INTEGER NOT NULL,
    generation BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    metric_token VARCHAR(100) NOT NULL,
    PRIMARY KEY(data_source_id,variable_id)
);
