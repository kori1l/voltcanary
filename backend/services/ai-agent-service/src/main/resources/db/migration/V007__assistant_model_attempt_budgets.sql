-- Assistant-v2 is opt-in. Ledger lifetime/access follows its existing conversation.
create table ai_model_budget_plans (
    plan_id uuid primary key,
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    user_id uuid not null,
    account_id uuid not null,
    policy_id varchar(160) not null,
    policy_version varchar(40) not null,
    limits_json text not null,
    revision bigint not null default 0,
    calls bigint not null default 0,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    cost_micros bigint not null default 0,
    deadline_at timestamptz not null,
    cancelled boolean not null default false
);
create table ai_model_budget_turns (
    plan_id uuid not null references ai_model_budget_plans(plan_id) on delete cascade,
    turn_id uuid not null,
    policy_json text not null,
    limits_json text not null,
    revision bigint not null default 0,
    calls bigint not null default 0,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    cost_micros bigint not null default 0,
    deadline_at timestamptz not null,
    primary key(plan_id, turn_id)
);
create table ai_model_budget_attempts (
    plan_id uuid not null,
    turn_id uuid not null,
    call_id uuid not null,
    attempt_json text not null,
    request_digest varchar(64) not null,
    status varchar(32) not null,
    charged_input bigint not null,
    charged_output bigint not null,
    charged_cost bigint not null,
    usage_json text,
    response_json text,
    created_at timestamptz not null,
    primary key(plan_id, call_id),
    foreign key(plan_id, turn_id) references ai_model_budget_turns(plan_id, turn_id) on delete cascade
);
