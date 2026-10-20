CREATE TABLE dashboard_owner_quotas (
    owner_user_id UUID PRIMARY KEY,
    active_limit INTEGER NOT NULL CHECK (active_limit BETWEEN 1 AND 1000)
);
