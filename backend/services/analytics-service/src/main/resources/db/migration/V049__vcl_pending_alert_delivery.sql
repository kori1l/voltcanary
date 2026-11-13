CREATE INDEX ix_vcl_outbox_alert_delivery
    ON vcl_keyed_outbox(output_kind, acknowledged_at, account_id, key_digest, output_id);
