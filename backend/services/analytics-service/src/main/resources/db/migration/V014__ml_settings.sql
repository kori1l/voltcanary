CREATE TABLE ml_settings (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    device_id UUID NULL,
    ml_enabled BOOLEAN NOT NULL,
    prediction_mode VARCHAR(48) NOT NULL,
    confidence_warning_threshold DOUBLE PRECISION NOT NULL,
    maintenance_behavior VARCHAR(32) NOT NULL,
    explanations_visible BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NULL,
    CONSTRAINT ck_ml_settings_threshold CHECK (confidence_warning_threshold BETWEEN 0 AND 1),
    CONSTRAINT ck_ml_settings_mode CHECK (ml_enabled OR prediction_mode = 'RULE_BASED_ONLY')
);
CREATE INDEX ix_ml_settings_account_default ON ml_settings(account_id);
CREATE UNIQUE INDEX ux_ml_settings_device_override ON ml_settings(device_id);
