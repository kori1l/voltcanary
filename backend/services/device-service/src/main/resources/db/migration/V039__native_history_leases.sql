CREATE TABLE cfg_native_history_lease (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL,
    generation UUID NOT NULL,
    through_data_version BIGINT NOT NULL CHECK(through_data_version > 0),
    idempotency_key VARCHAR(160) NOT NULL,
    duration_seconds INTEGER NOT NULL CHECK(duration_seconds BETWEEN 60 AND 86400),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(account_id,idempotency_key),
    FOREIGN KEY(dataset_id,generation) REFERENCES cfg_native_history_generation(dataset_id,generation)
);
CREATE INDEX cfg_native_history_lease_generation ON cfg_native_history_lease(account_id,dataset_id,generation,expires_at);
