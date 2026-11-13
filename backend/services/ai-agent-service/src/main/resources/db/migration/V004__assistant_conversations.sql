create table ai_conversations (
    id uuid primary key,
    user_id uuid not null,
    account_id uuid not null,
    title varchar(200) not null,
    revision bigint not null,
    last_plan_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null
);
create index ix_ai_conversations_owner on ai_conversations(user_id, account_id, created_at, id);
create index ix_ai_conversations_expiry on ai_conversations(expires_at);
create table ai_conversation_selection (
    user_id uuid not null,
    account_id uuid not null,
    conversation_id uuid references ai_conversations(id) on delete set null,
    primary key(user_id, account_id)
);
create table ai_conversation_requests (
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    request_id uuid not null,
    payload_hash varchar(64) not null,
    status varchar(32) not null,
    plan_id uuid,
    lease_until timestamptz not null,
    primary key(conversation_id, request_id)
);
create table ai_conversation_messages (
    id uuid primary key,
    conversation_id uuid not null references ai_conversations(id) on delete cascade,
    sequence bigint not null,
    request_id uuid not null,
    role varchar(16) not null,
    kind varchar(32) not null,
    display_text text not null,
    redacted boolean not null,
    plan_id uuid,
    task_id uuid,
    turn_id uuid,
    outcome varchar(32),
    created_at timestamptz not null,
    unique(conversation_id, sequence),
    unique(conversation_id, request_id, kind)
);
