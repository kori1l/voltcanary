package com.voltcanary.alert.configurable.domain;

import java.time.Instant;
import java.util.UUID;

public record EvidencePolicy(
    UUID id,
    UUID accountId,
    UUID actorId,
    UUID ruleId,
    UUID grantId,
    long grantEpoch,
    String grantDefinitionDigest,
    UUID reviewId,
    String canonicalDefinition,
    String definitionDigest,
    Instant expiresAt
) {
    @Override
    public String toString() {
        return "EvidencePolicy[id=" + id + "]";
    }
}
