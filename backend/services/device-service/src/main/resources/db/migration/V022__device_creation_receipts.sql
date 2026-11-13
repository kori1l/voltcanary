create table device_creation_receipts (
    user_id uuid not null,
    account_id uuid not null,
    idempotency_key varchar(160) not null,
    payload_digest varchar(64) not null,
    device_id uuid not null,
    created_at timestamp with time zone not null,
    retain_until timestamp with time zone not null,
    primary key(user_id, account_id, idempotency_key)
);
create index ix_device_creation_receipts_retention on device_creation_receipts(retain_until);
