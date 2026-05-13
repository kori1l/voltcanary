create table sensors (
    id uuid primary key,
    device_id uuid not null references devices (id) on delete cascade,
    name varchar(255) not null,
    type varchar(100) not null,
    unit varchar(50),
    created_at timestamp not null
);

create index idx_sensors_device_id on sensors (device_id);

create table data_sources (
    id uuid primary key,
    device_id uuid not null references devices (id) on delete cascade,
    name varchar(255) not null,
    type varchar(100) not null,
    connection_ref varchar(500),
    created_at timestamp not null
);

create index idx_data_sources_device_id on data_sources (device_id);

create table device_analytics_configs (
    id uuid primary key,
    device_id uuid not null unique references devices (id) on delete cascade,
    enabled boolean not null,
    config_json text,
    updated_at timestamp not null
);
