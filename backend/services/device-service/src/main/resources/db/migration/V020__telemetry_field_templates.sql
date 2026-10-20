CREATE TABLE telemetry_field_templates (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    device_type VARCHAR(64),
    version INTEGER NOT NULL,
    policy_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_telemetry_templates_owner ON telemetry_field_templates(owner_id);
