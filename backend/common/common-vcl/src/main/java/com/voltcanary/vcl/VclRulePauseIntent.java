package com.voltcanary.vcl;

import java.util.UUID;

/** Exact deployment identity reviewed before stopping a logical rule. */
public record VclRulePauseIntent(UUID ruleId, UUID grantId, UUID epoch, long revision, String bundleDigest) {
    public VclRulePauseIntent {
        if (ruleId == null || grantId == null || epoch == null || revision < 1 || !VclRuleCutoverIntent.digest(bundleDigest)) {
            throw new VclException("RULE_PAUSE_INTENT_INVALID", "");
        }
    }

    public String reviewDigest(UUID account, UUID actor, UUID review) {
        return VclCanonical.sha256(String.join("\n", "RULE_PAUSE_REVIEW/1", account.toString(), actor.toString(),
                review.toString(), ruleId.toString(), grantId.toString(), epoch.toString(), Long.toString(revision), bundleDigest));
    }
}
