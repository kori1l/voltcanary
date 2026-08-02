alter table ml_training_runs
    drop constraint if exists ck_ml_training_runs_family;

alter table ml_training_runs
    add constraint ck_ml_training_runs_family
        check (model_family in ('ANOMALY_DETECTION', 'DEGRADATION_SCORING', 'FAILURE_PREDICTION', 'FORECASTING'));

alter table ml_model_versions
    drop constraint if exists ck_ml_model_versions_family;

alter table ml_model_versions
    add constraint ck_ml_model_versions_family
        check (family in ('ANOMALY_DETECTION', 'DEGRADATION_SCORING', 'FAILURE_PREDICTION', 'FORECASTING'));
