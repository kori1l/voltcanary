alter table ml_model_versions
    add column artifact_manifest_uri varchar(1000);

alter table ml_model_versions
    add column artifact_size_bytes bigint;

alter table ml_model_versions
    add constraint ck_ml_model_versions_artifact_size
    check (artifact_size_bytes is null or artifact_size_bytes >= 0);
