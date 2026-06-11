alter table analytics_event_outbox add column if not exists next_attempt_at timestamp;
alter table analytics_event_outbox add column if not exists locked_at timestamp;
alter table analytics_event_outbox add column if not exists locked_by varchar(255);

update analytics_event_outbox
set next_attempt_at = created_at
where next_attempt_at is null;

create index if not exists idx_analytics_event_outbox_claim
    on analytics_event_outbox (status, next_attempt_at, created_at);

create index if not exists idx_analytics_event_outbox_locked_at
    on analytics_event_outbox (locked_at);
