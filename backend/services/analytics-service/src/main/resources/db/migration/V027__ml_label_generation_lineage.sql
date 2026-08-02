alter table ml_labels
    add column horizon_days integer;

alter table ml_labels
    add column generation_version varchar(128);

alter table ml_labels
    add column source_outcome_ids_json text;

alter table ml_labels
    add column lineage_hash varchar(128);

alter table ml_labels
    add constraint ck_ml_labels_horizon_days check (horizon_days is null or horizon_days > 0);

create index idx_ml_labels_dataset_horizon
    on ml_labels (dataset_version_id, horizon_days, source);
