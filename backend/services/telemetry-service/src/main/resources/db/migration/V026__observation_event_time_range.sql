CREATE INDEX ix_cfg_observation_generation_event_time
    ON cfg_observation(binding_id,input_generation,occurred_at,checkpoint);
