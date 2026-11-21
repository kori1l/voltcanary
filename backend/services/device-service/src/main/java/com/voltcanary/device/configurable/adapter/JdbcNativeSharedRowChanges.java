package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeSharedRowChanges;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Same reviewed CAS/receipt engine, using actual workspace actors and fresh scoped reference authority. */
@RequiredArgsConstructor
public final class JdbcNativeSharedRowChanges implements NativeSharedRowChanges {
    private final DataSource source;
    private final Clock clock;
    private final NativeWorkspaceIdentity identity;

    @Override
    public NativeRowReview reserve(NativeSharedAccess access, long schemaRevision, String key) {
        return reviews(access).reserve(access.account(), access.actor(), access.dataset(), schemaRevision, key);
    }

    @Override
    public NativeRowReview prepare(NativeSharedAccess access, UUID review, VclDocument document) {
        return reviews(access).prepare(access.account(), access.actor(), review, document);
    }

    @Override
    public NativeRowReview inspect(NativeSharedAccess access, UUID review) {
        return reviews(access).inspect(access.account(), access.actor(), review);
    }

    @Override
    public NativeRowReview apply(NativeSharedAccess access, UUID review, long revision, String digest, String key) {
        var reviews = reviews(access);
        var prepared = reviews.begin(access.account(), access.actor(), review, revision, digest, key);
        if (Set.of("SUCCEEDED", "FAILED").contains(prepared.status())) return prepared;
        try {
            return new JdbcNativeRowMutations(clock, transactions(access)).apply(access.account(), access.actor(), review);
        } catch (VclException failure) {
            if (Set.of("DATA_ACCESS_DENIED", "ACCESS_SCOPE_CHANGED", "ACCOUNT_ACCESS_REVOKED", "WORKSPACE_AUTHORITY_UNAVAILABLE", "NATIVE_GRANT_INTEGRITY").contains(failure.code())) throw failure;
            return reviews.outcome(access.account(), access.actor(), review,
                    failure.code().equals("ROW_COMMIT_UNCERTAIN") ? "UNKNOWN" : "FAILED", failure.code());
        }
    }

    private JdbcNativeRowReviews reviews(NativeSharedAccess access) {
        return new JdbcNativeRowReviews(clock, transactions(access));
    }

    private NativeDataTransactions transactions(NativeSharedAccess access) {
        return NativeDataTransactions.shared(source, identity, access, true);
    }
}
