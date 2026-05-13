create table alerts (
    id uuid primary key,
    device_id uuid not null,
    deduplication_key varchar(500) not null,
    title varchar(255) not null,
    description varchar(1000) not null,
    severity varchar(50) not null,
    status varchar(50) not null,
    created_at timestamp not null,
    acknowledged_at timestamp,
    resolved_at timestamp
);

create index idx_alerts_created_at
    on alerts (created_at desc);

create index idx_alerts_device_id_created_at
    on alerts (device_id, created_at desc);

create index idx_alerts_deduplication_key
    on alerts (deduplication_key);

create table alert_events (
    id uuid primary key,
    alert_id uuid not null,
    event_type varchar(100) not null,
    payload_json text not null,
    created_at timestamp not null
);

create index idx_alert_events_alert_id_created_at
    on alert_events (alert_id, created_at desc);

create table notification_channels (
    id uuid primary key,
    name varchar(255) not null,
    channel_type varchar(100) not null,
    config_json text not null,
    enabled boolean not null,
    created_at timestamp not null
);
