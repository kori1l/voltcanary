alter table ml_dataset_versions
    add column artifact_uri varchar(1000);

alter table ml_dataset_versions
    add column artifact_hash varchar(128);

alter table ml_dataset_versions
    add column immutable_row_reference varchar(1000);

alter table ml_dataset_versions
    add column data_hash varchar(128);

create index idx_ml_dataset_versions_artifact_hash
    on ml_dataset_versions (account_id, artifact_hash);

create index idx_ml_dataset_versions_data_hash
    on ml_dataset_versions (account_id, data_hash);
