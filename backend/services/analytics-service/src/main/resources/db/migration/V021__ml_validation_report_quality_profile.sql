alter table ml_validation_reports
    add column quality_profile_json text not null default '{}';
