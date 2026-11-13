create table ai_reviewed_plans (
    id uuid primary key,
    user_id uuid not null,
    account_id uuid not null,
    version integer not null,
    payload_digest varchar(64) not null,
    plan_json text not null,
    context_json text not null,
    status varchar(32) not null,
    result_json text,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    claimed_at timestamptz,
    updated_at timestamptz not null,
    retain_until timestamptz not null
);
create index ix_ai_reviewed_plans_owner on ai_reviewed_plans(user_id, account_id, id);
create index ix_ai_reviewed_plans_retention on ai_reviewed_plans(retain_until);
create table ai_plan_references (
    plan_id uuid not null references ai_reviewed_plans(id) on delete cascade,
    ref varchar(64) not null,
    kind varchar(32) not null,
    resource_id varchar(128) not null,
    summary_json text not null,
    primary key(plan_id, ref)
);
create table ai_plan_actions (
    plan_id uuid not null references ai_reviewed_plans(id) on delete cascade,
    action_id uuid not null,
    action_order integer not null,
    idempotency_key varchar(160) not null unique,
    status varchar(32) not null,
    result_json text,
    updated_at timestamptz not null,
    primary key(plan_id, action_id)
);
create table ai_plan_audit (
    id uuid primary key,
    plan_id uuid not null references ai_reviewed_plans(id) on delete cascade,
    user_id uuid not null,
    account_id uuid not null,
    version integer not null,
    action_id uuid,
    event_type varchar(64) not null,
    model_config_id uuid,
    created_at timestamptz not null
);
