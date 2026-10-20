CREATE TABLE workspaces (
    id UUID PRIMARY KEY, name VARCHAR(80) NOT NULL, owner_user_id UUID NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces(id), user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER','MANAGER','MEMBER')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REMOVED')),
    PRIMARY KEY (workspace_id,user_id)
);
CREATE TABLE workspace_teams (
    id UUID PRIMARY KEY, workspace_id UUID NOT NULL REFERENCES workspaces(id), name VARCHAR(80) NOT NULL,
    UNIQUE (workspace_id,id)
);
CREATE TABLE workspace_team_members (
    workspace_id UUID NOT NULL, team_id UUID NOT NULL, user_id UUID NOT NULL,
    FOREIGN KEY (workspace_id,team_id) REFERENCES workspace_teams(workspace_id,id),
    FOREIGN KEY (workspace_id,user_id) REFERENCES workspace_members(workspace_id,user_id),
    PRIMARY KEY (team_id,user_id)
);
CREATE TABLE workspace_audit (
    id UUID PRIMARY KEY, workspace_id UUID NOT NULL REFERENCES workspaces(id), actor_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL, subject_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
