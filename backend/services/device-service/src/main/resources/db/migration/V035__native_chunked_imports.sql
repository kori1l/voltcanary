-- Pre-production contract upgrade: old import-control plans/receipts are reset.
-- Native dataset rows, their history, generations and sealed query snapshots remain intact.
UPDATE cfg_native_generation SET status='ARCHIVED' WHERE import_job_id IS NOT NULL AND status IN ('PROVISIONING','READY');
UPDATE cfg_native_generation SET import_job_id=NULL WHERE import_job_id IS NOT NULL;
DELETE FROM cfg_import_job;
DELETE FROM cfg_import_artifact;

CREATE TABLE cfg_import_progress (
    job_id UUID PRIMARY KEY REFERENCES cfg_import_job(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL CHECK(status IN ('RUNNING','PARTIAL','SUCCEEDED','CANCELLED','FAILED')),
    revision BIGINT NOT NULL,
    next_row INTEGER NOT NULL,
    chunk_size INTEGER NOT NULL,
    generation UUID NOT NULL,
    expected_data_version BIGINT NOT NULL,
    outcome_code VARCHAR(64),
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX cfg_import_progress_pending ON cfg_import_progress(status,updated_at,job_id);
CREATE TABLE cfg_import_chunk (
    job_id UUID NOT NULL REFERENCES cfg_import_job(id),
    chunk_index INTEGER NOT NULL,
    from_row INTEGER NOT NULL,
    through_row INTEGER NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(job_id,chunk_index)
);
