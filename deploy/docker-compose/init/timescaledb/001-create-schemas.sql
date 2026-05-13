CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE SCHEMA IF NOT EXISTS telemetry;
CREATE SCHEMA IF NOT EXISTS analytics;

CREATE TABLE IF NOT EXISTS telemetry.measurements (
  device_id uuid NOT NULL,
  source_id text NOT NULL,
  recorded_at timestamptz NOT NULL,
  metric_name text,
  metric_value double precision,
  unit text,
  quality text,
  created_at timestamptz,
  active_power_w double precision,
  voltage_v double precision,
  current_a double precision,
  energy_wh double precision,
  relay_state text,
  rssi integer,
  raw_payload jsonb
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_measurements_device_source_time_metric
  ON telemetry.measurements (device_id, source_id, recorded_at, metric_name);

SELECT create_hypertable(
  'telemetry.measurements',
  'recorded_at',
  if_not_exists => TRUE
);
