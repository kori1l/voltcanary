alter table data_sources add column if not exists status varchar(50) not null default 'ACTIVE';
alter table data_sources add column if not exists ingestion_token_hash varchar(128);
alter table data_sources add column if not exists token_prefix varchar(32);
alter table data_sources add column if not exists token_created_at timestamp;
alter table data_sources add column if not exists token_last_used_at timestamp;
alter table data_sources add column if not exists token_revoked_at timestamp;
alter table data_sources add column if not exists updated_at timestamp not null default now();

create index if not exists idx_data_sources_token_prefix on data_sources (token_prefix);
create index if not exists idx_data_sources_name on data_sources (name);
