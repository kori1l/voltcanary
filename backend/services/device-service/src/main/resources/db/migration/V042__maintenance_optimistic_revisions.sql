alter table maintenance_operations add column row_revision bigint not null default 0;
alter table maintenance_windows add column row_revision bigint not null default 0;
alter table maintenance_outcomes add column row_revision bigint not null default 0;
