CREATE TABLE vcl_keyed_timers (
    account_id UUID NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    timer_id UUID NOT NULL,
    scheduled_revision BIGINT NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    timer_kind VARCHAR(32) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (account_id, key_digest, timer_id),
    FOREIGN KEY (account_id, key_digest) REFERENCES vcl_keyed_state(account_id, key_digest),
    CHECK (scheduled_revision > 0),
    CHECK (timer_kind IN ('WAIT_TIMEOUT','WINDOW_CLOSE','SESSION_TIMEOUT')),
    CHECK (LENGTH(payload) <= 65536)
);
CREATE INDEX idx_vcl_keyed_timers_due ON vcl_keyed_timers(account_id,due_at,key_digest,timer_id);
