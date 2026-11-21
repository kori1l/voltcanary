-- Typed values remain in each opt-in dense history table. These rows contain
-- only immutable capture/cut/checksum metadata, never an EAV value projection.
CREATE TABLE cfg_native_history_generation (
    dataset_id UUID NOT NULL,
    generation UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    schema_revision BIGINT NOT NULL,
    physical_id UUID NOT NULL,
    first_data_version BIGINT NOT NULL CHECK(first_data_version>0),
    last_data_version BIGINT,
    captured_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(dataset_id,generation),
    FOREIGN KEY(dataset_id,generation) REFERENCES cfg_native_generation(dataset_id,generation),
    CHECK(last_data_version IS NULL OR last_data_version>=first_data_version)
);
CREATE TABLE cfg_native_history_checkpoint (
    dataset_id UUID NOT NULL,
    generation UUID NOT NULL,
    record_id UUID NOT NULL,
    record_version BIGINT NOT NULL CHECK(record_version>0),
    data_version BIGINT NOT NULL CHECK(data_version>0),
    deleted BOOLEAN NOT NULL,
    capture_kind VARCHAR(16) NOT NULL CHECK(capture_kind IN ('BASELINE','MUTATION','RETIREMENT')),
    value_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY(dataset_id,generation,record_id,record_version),
    FOREIGN KEY(dataset_id,generation) REFERENCES cfg_native_history_generation(dataset_id,generation)
);
CREATE INDEX cfg_native_history_cut ON cfg_native_history_checkpoint(dataset_id,generation,data_version,record_id);
CREATE TABLE cfg_native_history_commit (
    dataset_id UUID NOT NULL,
    generation UUID NOT NULL,
    data_version BIGINT NOT NULL,
    row_count INTEGER NOT NULL CHECK(row_count>=0),
    record_set_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY(dataset_id,generation,data_version),
    FOREIGN KEY(dataset_id,generation) REFERENCES cfg_native_history_generation(dataset_id,generation)
);
