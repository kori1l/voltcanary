CREATE TABLE energy_tariff_revisions (
    tariff_id UUID NOT NULL, version INTEGER NOT NULL, account_id UUID NOT NULL REFERENCES accounts(id), workspace_id UUID,
    currency VARCHAR(3) NOT NULL, timezone VARCHAR(80) NOT NULL, rate_per_kwh NUMERIC(18,8) NOT NULL CHECK(rate_per_kwh>=0),
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL, effective_to TIMESTAMP WITH TIME ZONE NOT NULL, taxes_included BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK(effective_from<effective_to), PRIMARY KEY(tariff_id,version)
);
CREATE TABLE energy_tariff_archive (tariff_id UUID PRIMARY KEY, archived_by UUID NOT NULL, archived_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now());
CREATE INDEX tariff_scope_period ON energy_tariff_revisions(account_id,workspace_id,effective_from,effective_to);
CREATE TABLE operating_schedule_revisions (
    schedule_id UUID NOT NULL, version INTEGER NOT NULL, account_id UUID NOT NULL REFERENCES accounts(id), device_id UUID NOT NULL REFERENCES devices(id),
    timezone VARCHAR(80) NOT NULL, effective_from TIMESTAMP WITH TIME ZONE NOT NULL, effective_to TIMESTAMP WITH TIME ZONE NOT NULL,
    week_slots TEXT NOT NULL, holidays TEXT NOT NULL, reason VARCHAR(500) NOT NULL, created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(), CHECK(effective_from<effective_to), PRIMARY KEY(schedule_id,version)
);
CREATE TABLE operational_fact_revisions (
    event_id UUID NOT NULL, version INTEGER NOT NULL, account_id UUID NOT NULL REFERENCES accounts(id), device_id UUID NOT NULL REFERENCES devices(id),
    source_id UUID NOT NULL, source_event_id VARCHAR(160) NOT NULL, kind VARCHAR(20) NOT NULL,
    completeness VARCHAR(16) NOT NULL, start_at TIMESTAMP WITH TIME ZONE NOT NULL, end_at TIMESTAMP WITH TIME ZONE,
    reason VARCHAR(500) NOT NULL, evidence VARCHAR(500), created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK(end_at IS NULL OR start_at<end_at), PRIMARY KEY(event_id,version),
    UNIQUE(account_id,source_id,source_event_id,version)
);
CREATE INDEX operational_device_window ON operational_fact_revisions(account_id,device_id,start_at,end_at);
