create table if not exists telemetry_event_outbox (
    id uuid primary key,
    event_key varchar(500) not null unique,
    topic varchar(255) not null,
    message_key varchar(255) not null,
    payload_json text not null,
    status varchar(50) not null,
    attempts integer not null,
    next_attempt_at timestamp,
    locked_at timestamp,
    locked_by varchar(255),
    last_error varchar(1000),
    created_at timestamp not null,
    published_at timestamp
);

create index if not exists idx_telemetry_event_outbox_claim
    on telemetry_event_outbox (status, next_attempt_at, created_at);

create index if not exists idx_telemetry_event_outbox_locked_at
    on telemetry_event_outbox (locked_at);
