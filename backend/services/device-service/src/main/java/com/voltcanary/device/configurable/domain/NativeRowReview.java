package com.voltcanary.device.configurable.domain;

import java.time.Instant;
import java.util.UUID;

public record NativeRowReview(UUID id, UUID accountId, UUID actorId, UUID datasetId, long schemaRevision,
        UUID mutationId, UUID createdRecordId, UUID receiptId, String reservationKey, String status,
        long revision, Instant expiresAt, String canonicalDocument, String documentDigest,
        String semanticDigest, String reviewDigest, String scopeDigest, String outcomeCode, String resultJson) {
    @Override public String toString() { return "NativeRowReview[id=" + id + ", status=" + status + "]"; }
}
