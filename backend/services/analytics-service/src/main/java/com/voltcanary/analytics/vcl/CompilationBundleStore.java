package com.voltcanary.analytics.vcl;

import java.util.Optional;
import java.util.UUID;

/** Insert-only, account-scoped port. Replays preserve the original bytes; conflicting identity is rejected. */
public interface CompilationBundleStore {
    CompilationBundle put(CompilationBundle bundle);
    Optional<CompilationBundle> find(UUID accountId, UUID bundleId);
}
