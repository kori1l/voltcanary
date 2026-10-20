CREATE TABLE workspace_resource_accounts (
    workspace_id UUID NOT NULL, account_id UUID NOT NULL REFERENCES accounts(id), consented_by UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true, version BIGINT NOT NULL DEFAULT 1, PRIMARY KEY(workspace_id,account_id)
);
CREATE TABLE workspace_resource_grants (
    id UUID PRIMARY KEY, workspace_id UUID NOT NULL, account_id UUID NOT NULL, subject_id UUID NOT NULL,
    subject_kind VARCHAR(8) NOT NULL CHECK(subject_kind IN ('USER','TEAM')),
    action VARCHAR(32) NOT NULL, scope_kind VARCHAR(8) NOT NULL CHECK(scope_kind IN ('DEVICE','LOCATION')),
    resource_id UUID NOT NULL,
    FOREIGN KEY(workspace_id,account_id) REFERENCES workspace_resource_accounts(workspace_id,account_id),
    UNIQUE(workspace_id,account_id,subject_id,subject_kind,action,scope_kind,resource_id)
);
CREATE TABLE workspace_resource_audit (
    id UUID PRIMARY KEY, workspace_id UUID NOT NULL, account_id UUID NOT NULL, actor_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL, subject_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
