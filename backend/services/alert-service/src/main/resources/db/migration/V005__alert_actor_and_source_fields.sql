alter table alerts add column if not exists source_event_id uuid;
alter table alerts add column if not exists anomaly_id uuid;
alter table alerts add column if not exists acknowledged_by uuid;
alter table alerts add column if not exists resolved_by uuid;
alter table alerts add column if not exists assigned_to uuid;

alter table alert_events add column if not exists actor_id uuid;

create unique index if not exists uq_alerts_source_event_id
    on alerts (source_event_id);

create index if not exists idx_alerts_source_event_id
    on alerts (source_event_id);

create index if not exists idx_alerts_anomaly_id
    on alerts (anomaly_id);

create index if not exists idx_alert_events_actor_created_at
    on alert_events (actor_id, created_at desc);
