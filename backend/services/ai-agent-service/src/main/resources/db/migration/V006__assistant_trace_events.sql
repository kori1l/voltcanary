update ai_conversation_messages set turn_id=request_id where kind in ('INPUT','REPLY') and turn_id is null;
create table ai_assistant_trace_events (
    id uuid primary key,
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    sequence bigint not null,
    schema_version integer not null,
    message_id uuid,
    turn_id uuid not null,
    request_id uuid not null,
    attempt_id uuid not null,
    review_id uuid,
    action_id uuid,
    model_run_id uuid,
    stage varchar(48) not null,
    status varchar(24) not null,
    code varchar(96),
    duration_ms bigint,
    payload_json text not null,
    payload_bytes integer not null,
    truncated boolean not null,
    created_at timestamptz not null,
    unique(conversation_id, sequence)
);
create index ix_ai_trace_turn on ai_assistant_trace_events(conversation_id,turn_id,sequence);
