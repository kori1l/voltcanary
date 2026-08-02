create table maintenance_outcomes_read_model (
    maintenance_outcome_id uuid primary key,
    account_id uuid not null,
    device_id uuid not null,
    operation_id uuid,
    maintenance_window_id uuid,
    observed_at timestamp not null,
    resolved_at timestamp,
    outcome_type varchar(64) not null,
    failure_mode varchar(255),
    component varchar(255),
    severity varchar(32),
    downtime_minutes integer,
    source varchar(32) not null,
    confidence double precision not null,
    version bigint not null,
    updated_at timestamp not null
);

create index idx_analytics_maintenance_outcomes_device_observed
    on maintenance_outcomes_read_model (device_id, observed_at);

create index idx_analytics_maintenance_outcomes_account_type
    on maintenance_outcomes_read_model (account_id, outcome_type, observed_at);
