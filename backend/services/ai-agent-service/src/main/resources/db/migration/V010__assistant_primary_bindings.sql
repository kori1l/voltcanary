alter table ai_orchestration_requests add column client_revision bigint;
create table ai_orchestration_review_bindings (
    review_id uuid primary key references ai_reviewed_plans(id) on delete cascade,
    plan_id uuid not null references ai_orchestrations(id) on delete cascade,
    step_id varchar(40) not null
);
create index ai_orchestration_review_plan on ai_orchestration_review_bindings(plan_id);
create table ai_orchestration_contexts (
    plan_id uuid not null references ai_orchestrations(id) on delete cascade,
    request_id uuid not null,
    step_id varchar(40) not null,
    context_json text not null,
    primary key(plan_id,request_id,step_id)
);
