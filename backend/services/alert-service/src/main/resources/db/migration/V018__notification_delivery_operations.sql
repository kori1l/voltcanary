alter table notification_attempts
    alter column alert_id drop not null;

alter table notification_attempts
    add column if not exists max_attempts integer;

alter table notification_attempts
    add column if not exists retry_exhausted boolean;

alter table notification_attempts
    add column if not exists provider_message_id varchar(255);

alter table notification_attempts
    add column if not exists request_payload_json text;

update notification_attempts
set max_attempts = 3
where max_attempts is null;

update notification_attempts
set retry_exhausted = false
where retry_exhausted is null;

alter table notification_attempts
    alter column max_attempts set not null;

alter table notification_attempts
    alter column retry_exhausted set not null;

create index if not exists idx_notification_attempts_channel_event_created_at
    on notification_attempts (channel_id, delivery_event_type, created_at desc);
