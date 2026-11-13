ALTER TABLE cfg_source_coverage_attestation ADD COLUMN event_time_basis VARCHAR(32);

ALTER TABLE cfg_source_coverage_attestation ADD CONSTRAINT ck_source_coverage_event_time_basis
    CHECK (event_time_basis IS NULL OR
        (event_time_basis = 'SOURCE_EVENT_TIME_V1' AND accepted_at IS NOT NULL));
