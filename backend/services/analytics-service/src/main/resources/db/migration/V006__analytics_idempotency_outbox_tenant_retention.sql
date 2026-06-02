alter table device_baselines add column if not exists account_id uuid;
alter table feature_sets add column if not exists account_id uuid;
alter table anomalies add column if not exists account_id uuid;
alter table risk_scores add column if not exists account_id uuid;
alter table prediction_results add column if not exists account_id uuid;
alter table recommendations add column if not exists account_id uuid;
alter table analytics_jobs add column if not exists account_id uuid;

alter table analytics_jobs add column if not exists window_start timestamp;
alter table analytics_jobs add column if not exists window_end timestamp;
alter table analytics_jobs add column if not exists input_hash varchar(128);

alter table anomalies add column if not exists window_start timestamp;
alter table anomalies add column if not exists window_end timestamp;
alter table anomalies add column if not exists input_hash varchar(128);

alter table risk_scores add column if not exists explanation_json text;
alter table risk_scores add column if not exists window_start timestamp;
alter table risk_scores add column if not exists window_end timestamp;
alter table risk_scores add column if not exists input_hash varchar(128);

alter table prediction_results add column if not exists model_version varchar(255);
alter table prediction_results add column if not exists anomaly_score double precision;
alter table prediction_results add column if not exists degradation_score double precision;
alter table prediction_results add column if not exists confidence double precision;
alter table prediction_results add column if not exists risk_level varchar(50);
alter table prediction_results add column if not exists status varchar(50);

create index if not exists idx_analytics_jobs_device_window_hash
    on analytics_jobs (device_id, window_start, window_end, input_hash);

create index if not exists idx_anomalies_device_rule_window_hash
    on anomalies (device_id, rule_id, window_start, window_end, input_hash);

create index if not exists idx_risk_scores_device_window_hash
    on risk_scores (device_id, window_start, window_end, input_hash);

create index if not exists idx_device_baselines_account_device_calculated_at
    on device_baselines (account_id, device_id, calculated_at desc);

create index if not exists idx_anomalies_account_device_detected_at
    on anomalies (account_id, device_id, detected_at desc);

create table if not exists analytics_event_outbox (
    id uuid primary key,
    event_key varchar(255) not null unique,
    topic varchar(255) not null,
    message_key varchar(255) not null,
    payload_json text not null,
    status varchar(50) not null,
    attempts integer not null,
    last_error varchar(1000),
    created_at timestamp not null,
    published_at timestamp
);

create index if not exists idx_analytics_event_outbox_status_created_at
    on analytics_event_outbox (status, created_at);
