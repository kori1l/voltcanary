create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    token_hash varchar(255) not null unique,
    expires_at timestamp not null,
    revoked_at timestamp,
    created_at timestamp not null
);

create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
create index idx_refresh_tokens_expires_at on refresh_tokens (expires_at);
