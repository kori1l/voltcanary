create table notification_preferences (
    id uuid primary key,
    account_id uuid not null,
    user_id uuid,
    enabled boolean not null,
    severity_filter varchar(255) not null,
    channel_type_filter varchar(255) not null,
    device_filter text not null,
    quiet_hours_start time,
    quiet_hours_end time,
    timezone varchar(100) not null,
    critical_bypass_quiet_hours boolean not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_notification_preferences_account_user
    on notification_preferences (account_id, user_id);

create index idx_notification_preferences_account_enabled
    on notification_preferences (account_id, enabled);
