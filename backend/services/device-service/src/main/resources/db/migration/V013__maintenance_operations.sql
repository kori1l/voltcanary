create table maintenance_operations (
    id uuid primary key,
    account_id uuid not null references accounts (id) on delete cascade,
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
    constraint chk_maintenance_operation_range check (end_at > start_at)
);

alter table maintenance_windows
    add column operation_id uuid;

insert into maintenance_operations (
    id,
    account_id,
    title,
    description,
    start_at,
    end_at,
    suppress_alerts,
    exclude_from_analytics,
    exclude_from_ml,
    cancelled_at,
    cancellation_reason,
    created_by,
    updated_by,
    created_at,
    updated_at,
    version
)
select
    id,
    account_id,
    title,
    description,
    start_at,
    end_at,
    suppress_alerts,
    exclude_from_analytics,
    exclude_from_ml,
    cancelled_at,
    cancellation_reason,
    created_by,
    updated_by,
    created_at,
    updated_at,
    version
from maintenance_windows;

update maintenance_windows
set operation_id = id
where operation_id is null;

alter table maintenance_windows
    add constraint fk_maintenance_windows_operation
    foreign key (operation_id) references maintenance_operations (id) on delete cascade;

create index idx_maintenance_operations_account_time
    on maintenance_operations (account_id, start_at, end_at);

create index idx_maintenance_windows_operation
    on maintenance_windows (operation_id);
