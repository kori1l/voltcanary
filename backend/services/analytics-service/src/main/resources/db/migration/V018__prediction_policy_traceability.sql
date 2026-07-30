alter table prediction_results
    add column if not exists model_id uuid;

alter table prediction_results
    add column if not exists policy_source varchar(32);

alter table prediction_results
    add column if not exists feature_schema_hash varchar(128);

alter table prediction_results
    add column if not exists fallback_reason varchar(255);

alter table prediction_results
    add column if not exists shadow_prediction boolean not null default false;

create index if not exists idx_prediction_results_device_shadow_created
    on prediction_results (device_id, shadow_prediction, created_at desc);
