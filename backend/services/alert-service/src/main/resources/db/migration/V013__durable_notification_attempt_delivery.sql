alter table notification_attempts
    add column if not exists delivery_event_type varchar(100);

alter table notification_attempts
    add column if not exists idempotency_key varchar(500);

alter table notification_attempts
    add column if not exists next_attempt_at timestamp;

alter table notification_attempts
    add column if not exists locked_at timestamp;

alter table notification_attempts
    add column if not exists locked_by varchar(255);

update notification_attempts
set delivery_event_type = 'MANUAL',
    idempotency_key = cast(alert_id as varchar) || ':' || cast(channel_id as varchar) || ':MANUAL',
    next_attempt_at = created_at
where delivery_event_type is null;

alter table notification_attempts
    alter column delivery_event_type set not null;

alter table notification_attempts
    alter column idempotency_key set not null;

alter table notification_attempts
    alter column next_attempt_at set not null;

create unique index if not exists uq_notification_attempts_idempotency_key
    on notification_attempts (idempotency_key);

create index if not exists idx_notification_attempts_claim
    on notification_attempts (status, next_attempt_at, locked_at, created_at);
