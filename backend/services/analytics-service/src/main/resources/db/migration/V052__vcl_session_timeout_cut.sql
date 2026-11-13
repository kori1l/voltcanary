ALTER TABLE vcl_keyed_timers ADD COLUMN input_generation UUID;
ALTER TABLE vcl_keyed_timers ADD COLUMN input_checkpoint BIGINT;
ALTER TABLE vcl_keyed_timers ADD CONSTRAINT vcl_session_timeout_cut_check CHECK (
    (input_generation IS NULL AND input_checkpoint IS NULL)
    OR (timer_kind='SESSION_TIMEOUT' AND input_generation IS NOT NULL AND input_checkpoint IS NOT NULL AND input_checkpoint>=0)
);
