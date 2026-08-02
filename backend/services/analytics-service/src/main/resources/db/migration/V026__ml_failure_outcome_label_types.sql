alter table ml_labels
    drop constraint if exists ck_ml_labels_label;

alter table ml_label_audit
    drop constraint if exists ck_ml_label_audit_previous;

alter table ml_label_audit
    drop constraint if exists ck_ml_label_audit_new;

alter table ml_labels
    add constraint ck_ml_labels_label check (label in (
        'NORMAL',
        'ANOMALY',
        'DEGRADATION',
        'MAINTENANCE',
        'CONFIRMED_FAILURE',
        'CORRECTIVE_MAINTENANCE',
        'PREVENTIVE_MAINTENANCE',
        'INSPECTION_NORMAL',
        'FALSE_POSITIVE',
        'SENSOR_FAULT',
        'UNRESOLVED_ANOMALY',
        'UNKNOWN'
    ));

alter table ml_label_audit
    add constraint ck_ml_label_audit_previous check (previous_label is null or previous_label in (
        'NORMAL',
        'ANOMALY',
        'DEGRADATION',
        'MAINTENANCE',
        'CONFIRMED_FAILURE',
        'CORRECTIVE_MAINTENANCE',
        'PREVENTIVE_MAINTENANCE',
        'INSPECTION_NORMAL',
        'FALSE_POSITIVE',
        'SENSOR_FAULT',
        'UNRESOLVED_ANOMALY',
        'UNKNOWN'
    ));

alter table ml_label_audit
    add constraint ck_ml_label_audit_new check (new_label in (
        'NORMAL',
        'ANOMALY',
        'DEGRADATION',
        'MAINTENANCE',
        'CONFIRMED_FAILURE',
        'CORRECTIVE_MAINTENANCE',
        'PREVENTIVE_MAINTENANCE',
        'INSPECTION_NORMAL',
        'FALSE_POSITIVE',
        'SENSOR_FAULT',
        'UNRESOLVED_ANOMALY',
        'UNKNOWN'
    ));
