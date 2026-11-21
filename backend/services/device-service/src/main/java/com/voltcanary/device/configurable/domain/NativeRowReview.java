package com.voltcanary.device.configurable.domain;

import java.time.Instant;
import java.util.UUID;

public record NativeRowReview(UUID id, UUID accountId, UUID actorId, UUID datasetId, long schemaRevision,
        UUID mutationId, UUID createdRecordId, UUID receiptId, String reservationKey, String status,
        long revision, Instant expiresAt, String canonicalDocument, String documentDigest,
        String semanticDigest, String reviewDigest, String scopeDigest, String outcomeCode, String resultJson) {
    public NativeRowReview succeeded(String result) {
        return new NativeRowReview(id, accountId, actorId, datasetId, schemaRevision, mutationId, createdRecordId,
                receiptId, reservationKey, "SUCCEEDED", revision, expiresAt, canonicalDocument, documentDigest,
                semanticDigest, reviewDigest, scopeDigest, null, result);
    }

    @Override public String toString() { return "NativeRowReview[id=" + id + ", status=" + status + "]"; }
}
