alter table data_sources add column if not exists source_id varchar(255);
alter table data_sources add column if not exists source_type varchar(100);
alter table data_sources add column if not exists protocol varchar(50);
alter table data_sources add column if not exists enabled boolean not null default true;
alter table data_sources add column if not exists last_seen_at timestamp;
alter table data_sources add column if not exists credentials_ref varchar(255);

update data_sources
set source_id = name
where source_id is null;

update data_sources
set source_type = type
where source_type is null;

update data_sources
set protocol = connection_ref
where protocol is null;

update data_sources
set last_seen_at = token_last_used_at
where last_seen_at is null and token_last_used_at is not null;

update data_sources
set credentials_ref = token_prefix
where credentials_ref is null and token_prefix is not null;

alter table data_sources alter column source_id set not null;
alter table data_sources alter column source_type set not null;
alter table data_sources alter column protocol set not null;

create unique index if not exists uq_data_sources_device_source_id on data_sources (device_id, source_id);
create index if not exists idx_data_sources_source_id on data_sources (source_id);
create index if not exists idx_data_sources_status on data_sources (status);
