package com.voltcanary.alert.configurable.domain;

import java.time.Instant;
import java.util.UUID;

public record EvidencePolicyReview(UUID id, UUID accountId, UUID actorId, UUID grantId, UUID ruleId,
        long grantEpoch, String grantDefinitionDigest, UUID policyId, UUID receiptId, String reservationKey,
        long revision, String status, Instant expiresAt, String canonicalDefinition, String reviewDigest, String outcomeCode) {
    @Override public String toString() { return "EvidencePolicyReview[id=" + id + ", status=" + status + "]"; }
}
