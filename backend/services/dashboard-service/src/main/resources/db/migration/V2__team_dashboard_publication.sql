CREATE TABLE dashboard_workspaces (
    dashboard_id UUID PRIMARY KEY REFERENCES dashboards(id), workspace_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE dashboard_grants (
    dashboard_id UUID NOT NULL REFERENCES dashboard_workspaces(dashboard_id), subject_id UUID NOT NULL,
    subject_kind VARCHAR(8) NOT NULL CHECK(subject_kind IN ('USER','TEAM')), capability VARCHAR(24) NOT NULL,
    PRIMARY KEY(dashboard_id,subject_id,subject_kind,capability)
);
CREATE TABLE dashboard_author_drafts (
    dashboard_id UUID NOT NULL REFERENCES dashboards(id), author_user_id UUID NOT NULL,
    base_revision INTEGER NOT NULL, etag UUID NOT NULL, definition JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(dashboard_id,author_user_id)
);
CREATE TABLE dashboard_sharing_audit (
    id UUID PRIMARY KEY, dashboard_id UUID NOT NULL REFERENCES dashboards(id), actor_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL, subject_id UUID, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
