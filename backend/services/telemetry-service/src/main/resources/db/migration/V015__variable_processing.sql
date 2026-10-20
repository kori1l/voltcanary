CREATE TABLE variable_source_locks (
    source_id UUID PRIMARY KEY
);
CREATE TABLE variable_processing_bundles (
    source_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    digest VARCHAR(64) NOT NULL,
    account_id UUID NOT NULL,
    device_id UUID NOT NULL,
    bundle_json TEXT NOT NULL,
    PRIMARY KEY(source_id, generation)
);
CREATE TABLE variable_processing_cursor (
    id INTEGER PRIMARY KEY,
    cursor_value BIGINT NOT NULL
);
INSERT INTO variable_processing_cursor(id,cursor_value) VALUES(1,0);
CREATE TABLE variable_pending_events (
    event_id UUID PRIMARY KEY,
    payload_json TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    diagnostic VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_variable_pending_status ON variable_pending_events(status, updated_at);
