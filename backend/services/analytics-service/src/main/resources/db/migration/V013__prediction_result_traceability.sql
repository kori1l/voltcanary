alter table prediction_results add column if not exists input_hash varchar(128);

create index if not exists idx_prediction_results_device_input_hash
    on prediction_results (device_id, input_hash);
