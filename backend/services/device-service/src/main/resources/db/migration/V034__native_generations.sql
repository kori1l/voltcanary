ALTER TABLE cfg_dataset ADD COLUMN physical_id UUID;
UPDATE cfg_dataset SET physical_id=id;
ALTER TABLE cfg_dataset ALTER COLUMN physical_id SET NOT NULL;

CREATE TABLE cfg_native_generation (
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    generation UUID NOT NULL,
    physical_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL REFERENCES accounts(id),
    schema_revision BIGINT NOT NULL,
    import_job_id UUID UNIQUE,
    status VARCHAR(24) NOT NULL CHECK(status IN ('PROVISIONING','READY','ACTIVE','RETIRED','ARCHIVED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(dataset_id,generation)
);
INSERT INTO cfg_native_generation(dataset_id,generation,physical_id,account_id,schema_revision,status)
SELECT id,generation,physical_id,account_id,schema_revision,
    CASE WHEN status='READY' THEN 'ACTIVE' WHEN status='ARCHIVED' THEN 'ARCHIVED' ELSE 'PROVISIONING' END
FROM cfg_dataset;
CREATE INDEX cfg_native_generation_account ON cfg_native_generation(account_id,dataset_id,status);
