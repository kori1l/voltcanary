package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclMetadata;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Application boundary. The owner adapter supplies metadata, readiness and fresh authorization. */
@RequiredArgsConstructor
public final class CompilationBundlePreparation {

    /** Adapter is scoped to the verified actor/account/workspace; an account UUID alone grants no access. */
    public interface Owners {
        VclMetadata snapshot(UUID accountId, VclDocument document);
        CompilationOwnerValidation validate(
            UUID accountId,
            com.voltcanary.vcl.VclCompilation compilation
        );
        void reauthorize(UUID accountId, CompilationOwnerValidation validation);
    }

    private final VclCompiler compiler;
    private final Owners owners;
    private final CompilationBundleStore store;

    public CompilationBundle prepare(UUID accountId, UUID bundleId, VclDocument document) {
        if (document.kind() != VclDocument.Kind.RULE) throw new com.voltcanary.vcl.VclException(
            "RULE_REQUIRED",
            ""
        );
        var existing = store.find(accountId, bundleId);
        if (existing.isPresent()) {
            var bundle = existing.get();
            var validation = bundle.ownerValidation();
            owners.reauthorize(accountId, validation);
            if (
                !validation
                    .documentDigest()
                    .equals(com.voltcanary.vcl.VclCanonical.documentDigest(document))
            ) {
                throw new com.voltcanary.vcl.VclException("BUNDLE_ID_REUSED", "");
            }
            return bundle;
        }
        var compilation = compiler.compile(document, owners.snapshot(accountId, document));
        var validation = owners.validate(accountId, compilation);
        var bundle = CompilationBundle.seal(accountId, bundleId, compilation, validation);
        owners.reauthorize(accountId, validation);
        var stored = store.put(bundle);
        // A write can outlive the grant. Keep immutable replay state private until the owner reauthorizes it.
        owners.reauthorize(accountId, stored.ownerValidation());
        return stored;
    }

    public CompilationBundle inspect(UUID accountId, UUID bundleId) {
        // The adapter must reauthorize the stored pinned manifest before returning private data.
        var bundle = store
            .find(accountId, bundleId)
            .orElseThrow(() -> new com.voltcanary.vcl.VclException("BUNDLE_UNAVAILABLE", ""));
        var validation = bundle.ownerValidation();
        owners.reauthorize(accountId, validation);
        return bundle;
    }
}
