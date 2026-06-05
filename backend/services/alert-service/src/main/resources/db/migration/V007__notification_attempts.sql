create table notification_attempts (
    id uuid primary key,
    alert_id uuid not null,
    channel_id uuid not null,
    account_id uuid not null,
    channel_type varchar(100) not null,
    status varchar(50) not null,
    retry_count integer not null,
    last_error_code varchar(100),
    provider_response_summary varchar(1000),
    created_at timestamp not null,
    updated_at timestamp not null,
    sent_at timestamp
);

create index idx_notification_attempts_alert_created_at
    on notification_attempts (alert_id, created_at desc);

create index idx_notification_attempts_account_status_created_at
    on notification_attempts (account_id, status, created_at desc);

create index idx_notification_attempts_channel_status_created_at
    on notification_attempts (channel_id, status, created_at desc);
