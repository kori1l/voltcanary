package com.voltcanary.alert.configurable.domain;

import java.time.Instant;
import java.util.UUID;

/** Effective status incorporates the database expiry clock; permission never activates an evaluator. */
public record AutomationGrant(UUID id, UUID accountId, UUID actorId, UUID ruleId, UUID reviewId,
        String canonicalDefinition, String canonicalPins, String definitionDigest, long epoch, String status, Instant expiresAt) {
    public AutomationGrant effectiveAt(Instant now) {
        if (!status.equals("ACTIVE") || now.isBefore(expiresAt)) return this;
        return new AutomationGrant(id, accountId, actorId, ruleId, reviewId, canonicalDefinition, canonicalPins,
                definitionDigest, epoch, "EXPIRED", expiresAt);
    }
    @Override public String toString() { return "AutomationGrant[id=" + id + ", epoch=" + epoch + ", status=" + status + "]"; }
}
