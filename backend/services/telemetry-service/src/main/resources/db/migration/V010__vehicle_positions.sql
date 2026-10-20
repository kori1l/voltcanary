create table telemetry.positions (
    reading_id uuid not null,
    event_id uuid not null,
    device_id uuid not null,
    source_id varchar(255) not null,
    recorded_at timestamp with time zone not null,
    received_at timestamp with time zone not null,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    speed_kmh double precision check (speed_kmh between 0 and 300),
    heading_deg double precision check (heading_deg >= 0 and heading_deg < 360),
    quality varchar(500) not null,
    primary key (device_id, source_id, recorded_at)
);
create index idx_positions_latest on telemetry.positions (device_id, source_id, recorded_at desc);
