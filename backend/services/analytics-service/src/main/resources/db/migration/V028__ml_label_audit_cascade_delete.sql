alter table ml_label_audit
    drop constraint if exists fk_ml_label_audit_label;

alter table ml_label_audit
    add constraint fk_ml_label_audit_label
    foreign key (label_id) references ml_labels(id) on delete cascade;
