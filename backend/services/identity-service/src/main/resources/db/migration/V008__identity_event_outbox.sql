create table identity_event_outbox (
    id uuid primary key,
    event_key varchar(255) not null unique,
    topic varchar(255) not null,
    message_key varchar(255) not null,
    payload_json text not null,
    status varchar(50) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp,
    locked_at timestamp,
    locked_by varchar(255),
    last_error text,
    created_at timestamp not null,
    published_at timestamp
);

create index idx_identity_event_outbox_claim
    on identity_event_outbox (status, next_attempt_at, created_at);

create index idx_identity_event_outbox_stale_processing
    on identity_event_outbox (status, locked_at);
