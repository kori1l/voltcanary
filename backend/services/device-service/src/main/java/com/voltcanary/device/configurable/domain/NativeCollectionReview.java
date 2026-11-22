package com.voltcanary.device.configurable.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Durable owner review/reservation. No mutable caller document is retained. */
public record NativeCollectionReview(
    UUID id,
    UUID accountId,
    UUID actorId,
    UUID resourceId,
    UUID generation,
    UUID recordIdFieldId,
    UUID receiptId,
    Map<String, UUID> fieldIdsByKey,
    String reservationKey,
    String reservationDigest,
    String status,
    long revision,
    Instant expiresAt,
    String canonicalDocument,
    String semanticDigest,
    String reviewDigest,
    String scopeDigest,
    String applyKey,
    String outcomeCode
) {
    public NativeCollectionReview {
        fieldIdsByKey = Map.copyOf(fieldIdsByKey);
    }

    @Override
    public String toString() {
        return "NativeCollectionReview[id=" + id + ", status=" + status + "]";
    }
}
