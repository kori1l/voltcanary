package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.CompilationOwnerValidation;
import com.voltcanary.vcl.VclAutomationGrantDescription;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.util.UUID;
import java.util.function.Supplier;

/** Short-lived compile permission pins. Effect admission must use the atomic grant owner fence. */
public final class AutomationGrantSnapshot {
    private final VclAutomationGrantDescription original;
    private final Supplier<VclAutomationGrantDescription> refresh;

    AutomationGrantSnapshot(VclAutomationGrantDescription original, Supplier<VclAutomationGrantDescription> refresh) {
        this.original = original;
        this.refresh = refresh;
    }

    public void reauthorize() {
        if (!original.equals(refresh.get())) throw new VclException("AUTOMATION_GRANT_CHANGED", "");
    }

    public void require(VclMetadata.Resource kind, UUID resource, long revision, UUID field) {
        requireFields(kind, resource, revision, java.util.List.of(field));
    }

    /** One fresh owner read for a bounded schema; never one network call per field. */
    public void requireFields(VclMetadata.Resource kind, UUID resource, long revision, java.util.List<UUID> fields) {
        var requested = java.util.List.copyOf(fields);
        if (requested.isEmpty() || requested.size() > 64) throw new VclException("AUTOMATION_GRANT_SCOPE_DENIED", "");
        reauthorize();
        for (var field : requested) {
            if (!original.includes(kind, resource, revision, field)) throw new VclException("AUTOMATION_GRANT_SCOPE_DENIED", "");
        }
    }

    public CompilationOwnerValidation.ResourcePin pin() {
        reauthorize();
        // Include identity and epoch, not merely the immutable authored scope hash.
        String digest = VclCanonical.sha256("AUTOMATION_GRANT/1:" + original.id() + ":" + original.accountId() + ":"
                + original.actorId() + ":" + original.ruleId() + ":" + original.epoch() + ":" + original.status() + ":" + original.definitionDigest());
        return new CompilationOwnerValidation.ResourcePin(CompilationOwnerValidation.ResourceKind.EXECUTION_GRANT,
                original.id(), Long.parseLong(original.epoch()), digest);
    }

    public void requirePolicy(com.voltcanary.vcl.VclEvidencePolicyDescription policy) {
        reauthorize();
        if (!original.id().equals(policy.grantId()) || !original.ruleId().equals(policy.ruleId())
                || !original.actorId().equals(policy.actorId()) || !original.accountId().equals(policy.accountId())
                || !original.epoch().equals(policy.grantEpoch()) || !original.definitionDigest().equals(policy.grantDefinitionDigest())
                || policy.expiresAt().isAfter(original.expiresAt())) {
            throw new VclException("EVIDENCE_POLICY_GRANT_CHANGED", "");
        }
    }

    @Override public String toString() { return "AutomationGrantSnapshot[id=" + original.id() + "]"; }
}
