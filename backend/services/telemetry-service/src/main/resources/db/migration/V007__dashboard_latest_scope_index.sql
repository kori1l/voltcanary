CREATE INDEX IF NOT EXISTS idx_measurements_account_device_latest
ON telemetry.measurements(account_id,device_id,recorded_at DESC,metric_name,source_id DESC);
