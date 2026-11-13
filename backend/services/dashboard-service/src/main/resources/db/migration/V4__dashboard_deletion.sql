-- Keep immutable revisions and audit, but remove the dashboard from public access.
ALTER TABLE dashboards ADD COLUMN deleted_at TIMESTAMPTZ;
