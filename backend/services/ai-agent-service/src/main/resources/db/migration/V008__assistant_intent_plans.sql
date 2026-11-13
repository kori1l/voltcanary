create table ai_orchestrations (
    id uuid primary key,
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    user_id uuid not null,
    account_id uuid not null,
    revision bigint not null,
    schema_version integer not null default 1,
    state_json text not null,
    active_request_id uuid,
    expires_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
create index ix_ai_orchestrations_conversation on ai_orchestrations(conversation_id,updated_at,id);
create table ai_orchestration_requests (
    conversation_id uuid not null,
    request_id uuid not null,
    plan_id uuid not null references ai_orchestrations(id) on delete cascade,
    request_kind varchar(32) not null,
    payload_digest varchar(64) not null,
    request_json text not null,
    message_id uuid not null references ai_conversation_messages(id) on delete cascade,
    primary_call_id uuid not null,
    replan_call_id uuid not null,
    status varchar(32) not null,
    lease_until timestamp with time zone not null,
    answered_question_json text,
    primary key(conversation_id,request_id),
    foreign key(conversation_id,request_id) references ai_conversation_requests(conversation_id,request_id) on delete cascade
);
-- Retained independently of active/expired/completed orchestration state. Values are read from existing receipts.
create table ai_orchestration_resource_events (
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    sequence bigint not null,
    source_message_id uuid not null references ai_conversation_messages(id) on delete cascade,
    step_id varchar(40) not null,
    intent_id varchar(80) not null,
    fact_id varchar(160) not null,
    review_id uuid not null,
    action_id uuid not null,
    output_key varchar(40) not null,
    output_type varchar(80) not null,
    output_pointer varchar(500) not null,
    resource_kind varchar(32),
    primary key(conversation_id,sequence),
    unique(conversation_id,fact_id)
);
