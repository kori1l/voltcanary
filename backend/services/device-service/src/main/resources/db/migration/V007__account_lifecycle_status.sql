alter table accounts add column if not exists status varchar(50) not null default 'ACTIVE';
alter table accounts add column if not exists disabled_at timestamp;
alter table accounts add column if not exists deleted_at timestamp;

create index if not exists idx_accounts_status on accounts (status);
