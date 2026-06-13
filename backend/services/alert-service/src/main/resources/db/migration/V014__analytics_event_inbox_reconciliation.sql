create table alert_analytics_event_inbox (
    id uuid primary key,
    source_name varchar(255) not null,
    event_id uuid not null,
    event_type varchar(64) not null,
    payload_json text not null,
    status varchar(32) not null,
    alert_id uuid,
    attempts integer not null default 0,
    last_error varchar(1024),
    next_attempt_at timestamp not null,
    processing_started_at timestamp,
    processed_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (source_name, event_id)
);

create index idx_alert_analytics_inbox_due
    on alert_analytics_event_inbox (status, next_attempt_at, processing_started_at);

create index idx_alert_analytics_inbox_event
    on alert_analytics_event_inbox (source_name, event_id);
