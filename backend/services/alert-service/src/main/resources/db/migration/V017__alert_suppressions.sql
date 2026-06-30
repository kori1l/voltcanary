create table alert_suppressions (
    id uuid primary key,
    device_id uuid not null,
    alert_id uuid,
    source_event_id uuid,
    source_event_type varchar(128) not null,
    maintenance_window_id uuid not null,
    maintenance_window_version bigint not null,
    suppression_reason varchar(255) not null,
    event_time timestamp not null,
    created_at timestamp not null
);

create index idx_alert_suppressions_device_time
    on alert_suppressions (device_id, event_time);

create index idx_alert_suppressions_window
    on alert_suppressions (maintenance_window_id);

create unique index uq_alert_suppressions_source_event
    on alert_suppressions (source_event_id);
