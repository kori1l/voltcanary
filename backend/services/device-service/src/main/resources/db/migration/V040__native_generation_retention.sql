ALTER TABLE cfg_native_generation ADD COLUMN retired_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cfg_native_generation ADD COLUMN reclaimed_at TIMESTAMP WITH TIME ZONE;

-- The prior retirement time is unknown. Start its grace period at upgrade time.
UPDATE cfg_native_generation SET retired_at=CURRENT_TIMESTAMP WHERE status='RETIRED';
CREATE INDEX cfg_native_generation_retention ON cfg_native_generation(status,reclaimed_at,retired_at);
