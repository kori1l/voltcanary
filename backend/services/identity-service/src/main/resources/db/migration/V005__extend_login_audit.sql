alter table login_audit
    add column ip_address varchar(255);

alter table login_audit
    add column user_agent varchar(512);

alter table login_audit
    add column correlation_id varchar(255);

alter table login_audit
    add column failure_category varchar(255);

update login_audit
set failure_category = failure_reason
where failure_reason is not null;

create index idx_login_audit_correlation_id on login_audit (correlation_id);
create index idx_login_audit_failure_category on login_audit (failure_category);
