alter table ml_deployment_policies add column active_scope_key varchar(512);
update ml_deployment_policies set active_scope_key = case
    when deleted_at is not null then null
    when scope = 'SYSTEM' then 'SYSTEM'
    when scope = 'ACCOUNT' then concat('ACCOUNT:',cast(account_id as varchar))
    when scope = 'DEVICE_TYPE' then concat('DEVICE_TYPE:',cast(account_id as varchar),':',device_type)
    when scope = 'DEVICE' then concat('DEVICE:',cast(device_id as varchar))
end;
-- Keep historical duplicates, retaining the latest live policy for each scope.
update ml_deployment_policies set deleted_at = CURRENT_TIMESTAMP,
    deletion_reason = 'Superseded duplicate scope during consistency migration'
where id in (select id from (
    select id, row_number() over(partition by active_scope_key order by updated_at desc,id desc) as rn
    from ml_deployment_policies where deleted_at is null
) ranked where rn > 1);
update ml_deployment_policies set active_scope_key = null where deleted_at is not null;
drop index if exists ux_ml_deployment_policy_device;
create unique index ux_ml_deployment_policy_active_scope on ml_deployment_policies(active_scope_key);
alter table ml_deployment_policies add constraint ck_ml_policy_active_scope
    check ((deleted_at is null and active_scope_key is not null) or (deleted_at is not null and active_scope_key is null));
