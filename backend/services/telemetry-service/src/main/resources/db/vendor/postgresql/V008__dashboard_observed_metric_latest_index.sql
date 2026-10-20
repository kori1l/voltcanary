CREATE INDEX IF NOT EXISTS idx_measurements_observed_metric_latest
ON telemetry.measurements(device_id, metric_name, account_id, recorded_at DESC, source_id DESC)
INCLUDE (unit, metric_value, received_at, quality)
WHERE metric_name <> '_legacy' AND metric_value BETWEEN -1e308 AND 1e308;
