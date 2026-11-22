alter table maintenance_projection_checkpoints add column checkpoint_id uuid;
-- Re-read retained projections once so rows previously lost at timestamp ties are repaired.
update maintenance_projection_checkpoints set checkpoint_at = timestamp '1970-01-01 00:00:00';
