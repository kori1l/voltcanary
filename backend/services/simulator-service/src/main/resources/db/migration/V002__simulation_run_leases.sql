alter table simulation_runs
    add column active_key varchar(40);

alter table simulation_runs
    add column lease_owner varchar(255);

alter table simulation_runs
    add column lease_until timestamp;

update simulation_runs
set active_key = 'ACTIVE',
    lease_owner = 'migration',
    lease_until = current_timestamp
where status in ('STARTING', 'RUNNING', 'STOPPING')
  and active_key is null;

create unique index if not exists uq_simulation_runs_active_device
    on simulation_runs (device_id, active_key);

create index if not exists idx_simulation_runs_lease_until
    on simulation_runs (lease_until);
