package com.voltcanary.vcl;

import java.util.UUID;

/** Exact immutable inputs a user confirms before a RULE revision cutover. */
public record VclRuleCutoverIntent(UUID ruleId, UUID grantId, UUID predecessorEpoch, UUID successorEpoch,
        UUID bundleId, String bundleDigest, String checkpointDigest) {
    public VclRuleCutoverIntent {
        if (ruleId == null || grantId == null || successorEpoch == null
                || successorEpoch.equals(predecessorEpoch) || bundleId == null || !digest(bundleDigest)
                || !digest(checkpointDigest)) throw new VclException("RULE_CUTOVER_INTENT_INVALID", "");
    }

    public String reviewDigest(UUID account, UUID actor, UUID review, long grantEpoch) {
        return VclCanonical.sha256(String.join("\n", "RULE_CUTOVER_REVIEW/1", account.toString(), actor.toString(),
                review.toString(), ruleId.toString(), grantId.toString(), Long.toString(grantEpoch),
                predecessorEpoch == null ? "-" : predecessorEpoch.toString(),
                successorEpoch.toString(), bundleId.toString(), bundleDigest, checkpointDigest));
    }

    public static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
