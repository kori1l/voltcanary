alter table anomalies
    add column if not exists rule_id varchar(100);

alter table anomalies
    add column if not exists rule_version varchar(100);

create index if not exists idx_anomalies_device_rule_detected_at
    on anomalies (device_id, rule_id, detected_at desc);
