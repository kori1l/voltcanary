package com.voltcanary.alert.configurable.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal owner record; public views never serialize its idempotency key. */
public record GrantReview(
    UUID id,
    UUID accountId,
    UUID actorId,
    UUID ruleReviewId,
    UUID ruleId,
    UUID grantId,
    UUID receiptId,
    String reservationKey,
    long revision,
    String status,
    Instant expiresAt,
    String canonicalDefinition,
    String canonicalPins,
    String reviewDigest,
    String outcomeCode
) {
    @Override
    public String toString() {
        return "GrantReview[id=" + id + ", revision=" + revision + ", status=" + status + "]";
    }
}
