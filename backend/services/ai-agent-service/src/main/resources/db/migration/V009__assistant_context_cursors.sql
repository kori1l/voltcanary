-- No credentials, model text or domain data. Cursors expire independently of plan/receipt retention.
create table ai_context_cursors (
    id varchar(36) primary key,
    binding varchar(64) not null,
    after_sequence bigint not null,
    through_sequence bigint not null,
    expires_at timestamp with time zone not null
);
create index ai_context_cursors_expiry on ai_context_cursors(expires_at);
