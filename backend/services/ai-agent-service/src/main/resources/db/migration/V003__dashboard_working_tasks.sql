create table ai_dashboard_tasks (
    branch_id uuid primary key,
    task_id uuid not null,
    user_id uuid not null,
    account_id uuid not null,
    revision bigint not null,
    state_json text not null,
    active_turn_id uuid,
    expires_at timestamptz not null,
    retain_until timestamptz not null,
    updated_at timestamptz not null
);
create index ix_ai_dashboard_tasks_owner on ai_dashboard_tasks(user_id, account_id, task_id, branch_id);
create index ix_ai_dashboard_tasks_retention on ai_dashboard_tasks(retain_until);
create table ai_dashboard_turns (
    turn_id uuid primary key,
    request_id uuid not null,
    user_id uuid not null,
    account_id uuid not null,
    branch_id uuid not null references ai_dashboard_tasks(branch_id) on delete cascade,
    payload_hash varchar(64) not null,
    revision bigint not null,
    status varchar(32) not null,
    review_id uuid references ai_reviewed_plans(id) on delete set null,
    turn_json text not null,
    lease_until timestamptz not null,
    unique(user_id, account_id, request_id)
);
create index ix_ai_dashboard_turns_review on ai_dashboard_turns(user_id, account_id, review_id);
create index ix_ai_dashboard_turns_lease on ai_dashboard_turns(status, lease_until);
