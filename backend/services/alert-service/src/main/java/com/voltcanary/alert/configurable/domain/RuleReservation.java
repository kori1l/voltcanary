package com.voltcanary.alert.configurable.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Reserved identities only; this record cannot authorize a rule or a notification. */
public record RuleReservation(
    UUID reviewId,
    UUID ruleId,
    UUID receiptId,
    Map<String, UUID> outputFieldIdsByKey,
    Instant expiresAt,
    String status
) {
    public RuleReservation {
        outputFieldIdsByKey = Map.copyOf(outputFieldIdsByKey);
    }
}
