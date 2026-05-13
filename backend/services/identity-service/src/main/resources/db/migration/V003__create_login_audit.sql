create table login_audit (
    id uuid primary key,
    user_id uuid references users (id) on delete set null,
    email varchar(255) not null,
    successful boolean not null,
    failure_reason varchar(255),
    created_at timestamp not null
);

create index idx_login_audit_user_id on login_audit (user_id);
create index idx_login_audit_email on login_audit (email);
create index idx_login_audit_created_at on login_audit (created_at);
