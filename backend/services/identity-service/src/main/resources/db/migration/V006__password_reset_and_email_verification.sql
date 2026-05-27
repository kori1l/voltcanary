alter table users
    add column email_verified boolean not null default false;

create table password_reset_tokens (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamp not null,
    used_at timestamp,
    created_at timestamp not null
);

create index idx_password_reset_tokens_user_id on password_reset_tokens (user_id);
create index idx_password_reset_tokens_expires_at on password_reset_tokens (expires_at);

create table email_verification_tokens (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamp not null,
    used_at timestamp,
    created_at timestamp not null
);

create index idx_email_verification_tokens_user_id on email_verification_tokens (user_id);
create index idx_email_verification_tokens_expires_at on email_verification_tokens (expires_at);
