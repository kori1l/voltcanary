CREATE INDEX IF NOT EXISTS idx_measurements_energy_stream
ON telemetry.measurements(device_id, source_id, metric_name, recorded_at);
