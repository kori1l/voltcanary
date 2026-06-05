alter table alerts add column if not exists account_id uuid;

create index if not exists idx_alerts_account_created_at
    on alerts (account_id, created_at desc);

create index if not exists idx_alerts_account_status_created_at
    on alerts (account_id, status, created_at desc);

create unique index if not exists uq_alerts_deduplication_key
    on alerts (deduplication_key);
