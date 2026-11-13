package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.CompilationOwnerValidation;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclEvidencePolicyDescription;
import com.voltcanary.vcl.VclException;
import java.util.function.Supplier;

/** Compile policy pin only; no evidence persistence, retention execution or autonomous effect permit. */
public final class EvidencePolicySnapshot {
    private final VclEvidencePolicyDescription original;
    private final Supplier<VclEvidencePolicyDescription> refresh;
    EvidencePolicySnapshot(VclEvidencePolicyDescription original, Supplier<VclEvidencePolicyDescription> refresh) {
        this.original = original;
        this.refresh = refresh;
    }
    public void reauthorize() {
        if (!original.equals(refresh.get())) throw new VclException("EVIDENCE_POLICY_CHANGED", "");
    }
    public CompilationOwnerValidation.ResourcePin pin() {
        reauthorize();
        String digest = VclCanonical.sha256("EVIDENCE_POLICY/1:" + original.id() + ":" + original.accountId() + ":" + original.actorId()
                + ":" + original.ruleId() + ":" + original.grantId() + ":" + original.grantEpoch() + ":" + original.grantDefinitionDigest()
                + ":" + original.expiresAt() + ":" + original.definitionDigest());
        return new CompilationOwnerValidation.ResourcePin(CompilationOwnerValidation.ResourceKind.EVIDENCE_POLICY, original.id(), 1, digest);
    }
    @Override public String toString() { return "EvidencePolicySnapshot[id=" + original.id() + "]"; }
}
