alter table notification_channels
    add column account_id uuid;

update notification_channels
set account_id = '00000000-0000-0000-0000-000000000000'
where account_id is null;

alter table notification_channels
    alter column account_id set not null;

alter table notification_channels
    add column credentials_ref varchar(500);

alter table notification_channels
    add column updated_at timestamp;

update notification_channels
set updated_at = created_at
where updated_at is null;

alter table notification_channels
    alter column updated_at set not null;

create index idx_notification_channels_account_created_at
    on notification_channels (account_id, created_at desc);

create index idx_notification_channels_account_enabled
    on notification_channels (account_id, enabled);
