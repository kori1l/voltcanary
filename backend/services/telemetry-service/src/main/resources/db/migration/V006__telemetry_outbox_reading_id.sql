alter table telemetry_event_outbox
    add column if not exists reading_id uuid;

create unique index if not exists uq_telemetry_event_outbox_reading_id
    on telemetry_event_outbox (reading_id);
