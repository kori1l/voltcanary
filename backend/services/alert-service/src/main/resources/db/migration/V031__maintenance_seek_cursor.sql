alter table maintenance_projection_checkpoints add column checkpoint_id uuid;
update maintenance_projection_checkpoints set checkpoint_at = timestamp '1970-01-01 00:00:00';
