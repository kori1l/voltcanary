create table recovery_mail_outbox (
    id uuid primary key,
    payload_ciphertext text,
    status varchar(16) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp not null,
    locked_at timestamp,
    locked_by varchar(128),
    created_at timestamp not null,
    expires_at timestamp not null,
    last_error_code varchar(128)
);
create index idx_recovery_mail_due on recovery_mail_outbox(status,next_attempt_at);
create index idx_recovery_mail_expiry on recovery_mail_outbox(expires_at);
