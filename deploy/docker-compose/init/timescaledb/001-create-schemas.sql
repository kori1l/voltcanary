CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE SCHEMA IF NOT EXISTS telemetry;
CREATE SCHEMA IF NOT EXISTS analytics;

CREATE TABLE IF NOT EXISTS telemetry.measurements (
  device_id uuid NOT NULL,
  source_id text NOT NULL,
  recorded_at timestamptz NOT NULL,
  active_power_w double precision,
  voltage_v double precision,
  current_a double precision,
  energy_wh double precision,
  relay_state text,
  rssi integer,
  raw_payload jsonb,
  PRIMARY KEY (device_id, recorded_at)
);

SELECT create_hypertable(
  'telemetry.measurements',
  'recorded_at',
  if_not_exists => TRUE
);
