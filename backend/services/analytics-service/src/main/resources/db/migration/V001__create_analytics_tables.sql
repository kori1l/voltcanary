create table device_baselines (
    id uuid primary key,
    device_id uuid not null,
    baseline_json text not null,
    calculated_at timestamp not null
);

create index idx_device_baselines_device_id_calculated_at
    on device_baselines (device_id, calculated_at desc);

create table feature_sets (
    id uuid primary key,
    device_id uuid not null,
    window_start timestamp not null,
    window_end timestamp not null,
    features_json text not null
);

create index idx_feature_sets_device_id_window_end
    on feature_sets (device_id, window_end desc);

create table anomalies (
    id uuid primary key,
    device_id uuid not null,
    severity varchar(50) not null,
    score double precision not null,
    description varchar(1000) not null,
    detected_at timestamp not null
);

create index idx_anomalies_device_id_detected_at
    on anomalies (device_id, detected_at desc);

create table risk_scores (
    id uuid primary key,
    device_id uuid not null,
    score double precision not null,
    level varchar(50) not null,
    calculated_at timestamp not null
);

create index idx_risk_scores_device_id_calculated_at
    on risk_scores (device_id, calculated_at desc);

create table prediction_results (
    id uuid primary key,
    device_id uuid not null,
    model_name varchar(255) not null,
    prediction_json text not null,
    created_at timestamp not null
);

create index idx_prediction_results_device_id_created_at
    on prediction_results (device_id, created_at desc);

create table recommendations (
    id uuid primary key,
    device_id uuid not null,
    title varchar(255) not null,
    description varchar(1000) not null,
    priority varchar(50) not null,
    created_at timestamp not null
);

create index idx_recommendations_device_id_created_at
    on recommendations (device_id, created_at desc);

create table analytics_jobs (
    id uuid primary key,
    device_id uuid not null,
    job_type varchar(100) not null,
    status varchar(50) not null,
    created_at timestamp not null,
    completed_at timestamp
);

create index idx_analytics_jobs_device_id_created_at
    on analytics_jobs (device_id, created_at desc);
