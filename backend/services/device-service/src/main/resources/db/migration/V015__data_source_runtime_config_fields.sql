alter table data_sources add column if not exists mapping_json text;
alter table data_sources add column if not exists adapter_config_json text;
alter table data_sources add column if not exists credential_references_json text;
alter table data_sources add column if not exists home_region varchar(64) not null default 'default';
