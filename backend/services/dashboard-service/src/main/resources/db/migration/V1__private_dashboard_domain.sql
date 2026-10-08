CREATE TABLE dashboards (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    account_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    published_revision INTEGER NOT NULL DEFAULT 0 CHECK (published_revision >= 0),
    schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX dashboards_owner_cursor ON dashboards(owner_user_id, account_id, created_at, id);

CREATE TABLE dashboard_drafts (
    dashboard_id UUID PRIMARY KEY REFERENCES dashboards(id),
    author_user_id UUID NOT NULL,
    base_revision INTEGER NOT NULL CHECK (base_revision >= 0),
    etag UUID NOT NULL,
    definition JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE dashboard_revisions (
    dashboard_id UUID NOT NULL REFERENCES dashboards(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    definition JSONB NOT NULL,
    published_by UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (dashboard_id, revision)
);

CREATE TABLE dashboard_preferences (
    user_id UUID NOT NULL,
    account_id UUID NOT NULL,
    default_dashboard_id UUID REFERENCES dashboards(id),
    favorites JSONB NOT NULL DEFAULT '[]'::jsonb,
    default_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    fallback_reason VARCHAR(48),
    etag UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, account_id)
);

CREATE TABLE dashboard_idempotency (
    user_id UUID NOT NULL,
    account_id UUID NOT NULL,
    route VARCHAR(180) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    body_hash CHAR(64) NOT NULL,
    outcome JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, account_id, route, key_hash)
);
CREATE INDEX dashboard_idempotency_expiry ON dashboard_idempotency(expires_at);

CREATE TABLE dashboard_lifecycle_audit (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES dashboards(id),
    actor_user_id UUID NOT NULL,
    account_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    action VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

-- Revisions may only be appended. No runtime operation may overwrite history.
CREATE FUNCTION reject_dashboard_revision_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Dashboard revisions are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER dashboard_revisions_immutable BEFORE UPDATE OR DELETE ON dashboard_revisions
FOR EACH ROW EXECUTE FUNCTION reject_dashboard_revision_mutation();
