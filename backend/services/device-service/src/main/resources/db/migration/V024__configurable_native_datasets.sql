CREATE TABLE cfg_dataset (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    created_by UUID NOT NULL,
    schema_revision BIGINT NOT NULL,
    generation UUID NOT NULL,
    data_version BIGINT NOT NULL,
    record_id_field_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL CHECK(status IN ('PROVISIONING','READY','FAILED','ARCHIVED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX cfg_dataset_account ON cfg_dataset(account_id,id);
CREATE TABLE cfg_schema_revision (
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    revision BIGINT NOT NULL,
    document_digest VARCHAR(64) NOT NULL,
    canonical_document TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(dataset_id,revision)
);
CREATE TABLE cfg_field (
    dataset_id UUID NOT NULL,
    schema_revision BIGINT NOT NULL,
    field_id UUID NOT NULL,
    definition_json TEXT NOT NULL,
    PRIMARY KEY(dataset_id,schema_revision,field_id),
    FOREIGN KEY(dataset_id,schema_revision) REFERENCES cfg_schema_revision(dataset_id,revision)
);
CREATE TABLE cfg_index_manifest (
    dataset_id UUID NOT NULL,
    schema_revision BIGINT NOT NULL,
    field_id UUID NOT NULL,
    physical_name VARCHAR(63) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PLANNED','READY')),
    PRIMARY KEY(dataset_id,schema_revision,field_id),
    FOREIGN KEY(dataset_id,schema_revision) REFERENCES cfg_schema_revision(dataset_id,revision)
);
