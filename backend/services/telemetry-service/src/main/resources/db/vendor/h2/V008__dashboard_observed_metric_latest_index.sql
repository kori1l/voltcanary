-- H2 development/test compatibility. Covering partial indexes are PostgreSQL-specific.
CREATE INDEX IF NOT EXISTS idx_measurements_observed_metric_latest
ON telemetry.measurements(device_id, metric_name, account_id, recorded_at DESC, source_id DESC);
