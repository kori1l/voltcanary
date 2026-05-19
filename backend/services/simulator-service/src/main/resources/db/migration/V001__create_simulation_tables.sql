create table simulation_configs (
    id uuid primary key,
    account_id uuid not null,
    device_id uuid not null unique,
    data_source_id uuid not null,
    name varchar(255) not null,
    profile varchar(80) not null,
    protocol varchar(40) not null,
    yaml_config text not null,
    yaml_version integer not null,
    status varchar(40) not null,
    source_token text,
    created_at timestamp not null,
    updated_at timestamp not null,
    created_by uuid,
    updated_by uuid
);

create table simulation_config_versions (
    id uuid primary key,
    config_id uuid not null references simulation_configs (id) on delete cascade,
    version integer not null,
    yaml_config text not null,
    change_note varchar(500),
    created_at timestamp not null,
    created_by uuid
);

create table simulation_runs (
    id uuid primary key,
    config_id uuid not null references simulation_configs (id) on delete cascade,
    account_id uuid not null,
    device_id uuid not null,
    data_source_id uuid not null,
    status varchar(40) not null,
    started_at timestamp not null,
    stopped_at timestamp,
    started_by uuid,
    stop_reason varchar(500),
    generated_points bigint not null default 0,
    last_error text
);

create table simulation_run_events (
    id uuid primary key,
    run_id uuid not null references simulation_runs (id) on delete cascade,
    event_type varchar(80) not null,
    message text not null,
    created_at timestamp not null
);

create index idx_simulation_runs_device_id on simulation_runs (device_id);
create index idx_simulation_run_events_run_id on simulation_run_events (run_id);
