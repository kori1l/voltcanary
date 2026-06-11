alter table alert_events
    add column if not exists deduplication_key varchar(255);

update alert_events
set deduplication_key = cast(alert_id as varchar) || ':ESCALATED'
where event_type = 'ESCALATED'
  and deduplication_key is null;

create unique index if not exists uq_alert_events_deduplication_key
    on alert_events (deduplication_key);
