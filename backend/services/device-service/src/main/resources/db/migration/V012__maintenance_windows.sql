create table maintenance_windows (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    device_id uuid not null references devices (id) on delete cascade,
    title varchar(255) not null,
    description text,
    start_at timestamp not null,
    end_at timestamp not null,
    suppress_alerts boolean not null default true,
    exclude_from_analytics boolean not null default true,
    exclude_from_ml boolean not null default true,
    cancelled_at timestamp,
    cancellation_reason varchar(1000),
    created_by uuid,
    updated_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null,
    version bigint not null default 1,
    constraint chk_maintenance_window_range check (end_at > start_at)
);

create index idx_maintenance_windows_device_time
    on maintenance_windows (device_id, start_at, end_at);

create index idx_maintenance_windows_account_time
    on maintenance_windows (account_id, start_at, end_at);

create index idx_maintenance_windows_active_lookup
    on maintenance_windows (device_id, cancelled_at, start_at, end_at);
