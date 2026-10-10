create table energy_meter_revisions (
    id uuid primary key,
    account_id uuid not null references accounts(id) on delete cascade,
    device_id uuid not null references devices(id) on delete cascade,
    revision bigint not null check (revision > 0),
    physical_meter_id uuid not null,
    parent_physical_meter_id uuid,
    source_id varchar(160) not null,
    authoritative_method varchar(32) not null check (authoritative_method in ('COUNTER_DELTA','POWER_INTEGRAL')),
    max_gap_seconds bigint not null check (max_gap_seconds between 1 and 86400),
    effective_from timestamp with time zone not null,
    effective_to timestamp with time zone,
    active_marker smallint default 1,
    counter_events text not null default '[]',
    created_at timestamp with time zone not null default now(),
    unique(device_id, revision),
    unique(device_id, effective_from),
    unique(device_id, active_marker),
    check (effective_to is null or effective_to > effective_from),
    check ((effective_to is null and active_marker is not null and active_marker=1) or (effective_to is not null and active_marker is null)),
    check (parent_physical_meter_id is null or parent_physical_meter_id <> physical_meter_id)
);
create index idx_energy_meter_current on energy_meter_revisions(device_id,effective_to);
create index idx_energy_meter_account_physical on energy_meter_revisions(account_id,physical_meter_id);
