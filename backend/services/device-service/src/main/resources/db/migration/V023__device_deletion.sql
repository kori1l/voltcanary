ALTER TABLE devices ADD COLUMN deleted_at TIMESTAMP;
CREATE TABLE device_deletions (
    device_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    user_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    device_type VARCHAR(100) NOT NULL,
    delete_history BOOLEAN NOT NULL,
    source_ids_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    completed_steps VARCHAR(255) NOT NULL,
    last_error VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_device_deletions_owner ON device_deletions(account_id,created_at);
