create table if not exists alert_rules (
    id uuid primary key,
    account_id uuid not null,
    name varchar(160) not null,
    description varchar(1000),
    rule_type varchar(64) not null,
    enabled boolean not null default true,
    scope_type varchar(32) not null,
    scope_id uuid,
    device_type varchar(100),
    metric varchar(120) not null,
    condition_type varchar(64) not null,
    threshold numeric(18,6),
    threshold_unit varchar(32),
    baseline_multiplier numeric(10,4),
    min_points integer,
    severity varchar(32) not null,
    cooldown_seconds integer not null default 900,
    deduplication_window_seconds integer not null default 900,
    notification_channel_ids_json text not null default '[]',
    config_json text not null default '{}',
    version integer not null default 1,
    created_by uuid,
    updated_by uuid,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index if not exists idx_alert_rules_account
    on alert_rules (account_id);

create index if not exists idx_alert_rules_scope
    on alert_rules (account_id, scope_type, scope_id);

create index if not exists idx_alert_rules_type_metric
    on alert_rules (account_id, rule_type, metric);

create unique index if not exists uq_alert_rules_account_name
    on alert_rules (account_id, name);
