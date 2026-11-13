-- One plan-owned JSON snapshot, never an EAV table or a second model-dispatch authority.
create table ai_orchestration_collaboration (
    plan_id uuid primary key references ai_orchestrations(id) on delete cascade,
    revision bigint not null,
    state_json text not null,
    updated_at timestamp with time zone not null
);
-- Append-only under the plan lock. A repeated key cannot mutate state twice.
create table ai_orchestration_collaboration_events (
    plan_id uuid not null references ai_orchestrations(id) on delete cascade,
    revision bigint not null,
    request_key varchar(160) not null,
    request_digest varchar(64) not null,
    turn_id uuid not null,
    before_digest varchar(64) not null,
    after_digest varchar(64) not null,
    state_json text not null,
    created_at timestamp with time zone not null,
    primary key(plan_id, revision),
    unique(plan_id, request_key)
);
