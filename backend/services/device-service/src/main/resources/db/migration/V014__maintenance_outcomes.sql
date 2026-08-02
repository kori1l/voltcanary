create table maintenance_outcomes (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    device_id uuid not null references devices (id) on delete cascade,
    operation_id uuid references maintenance_operations (id) on delete set null,
    maintenance_window_id uuid references maintenance_windows (id) on delete set null,
    observed_at timestamp not null,
    resolved_at timestamp,
    outcome_type varchar(64) not null,
    failure_mode varchar(255),
    component varchar(255),
    severity varchar(32),
    downtime_minutes integer,
    technician_notes text,
    source varchar(32) not null default 'OPERATOR',
    confidence double precision not null default 1.0,
    created_by uuid,
    updated_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 1,
    constraint chk_maintenance_outcomes_resolution check (resolved_at is null or resolved_at >= observed_at),
    constraint chk_maintenance_outcomes_downtime check (downtime_minutes is null or downtime_minutes >= 0),
    constraint chk_maintenance_outcomes_confidence check (confidence >= 0.0 and confidence <= 1.0),
    constraint chk_maintenance_outcomes_type check (outcome_type in (
        'CONFIRMED_FAILURE',
        'CORRECTIVE_MAINTENANCE',
        'PREVENTIVE_MAINTENANCE',
        'INSPECTION_NORMAL',
        'FALSE_POSITIVE',
        'SENSOR_FAULT',
        'UNRESOLVED_ANOMALY',
        'UNKNOWN'
    )),
    constraint chk_maintenance_outcomes_source check (source in ('OPERATOR', 'TECHNICIAN', 'IMPORTED', 'SYSTEM')),
    constraint chk_maintenance_outcomes_severity check (severity is null or severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create index idx_maintenance_outcomes_account_operation
    on maintenance_outcomes (account_id, operation_id, observed_at);

create index idx_maintenance_outcomes_device_observed
    on maintenance_outcomes (device_id, observed_at);

create index idx_maintenance_outcomes_updated
    on maintenance_outcomes (updated_at);
