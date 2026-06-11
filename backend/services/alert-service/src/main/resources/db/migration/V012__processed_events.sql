create table processed_events (
    id uuid primary key,
    source_name varchar(255) not null,
    event_id uuid not null,
    processed_at timestamp not null,
    unique (source_name, event_id)
);

create index idx_alert_processed_events_processed_at
    on processed_events (processed_at);
