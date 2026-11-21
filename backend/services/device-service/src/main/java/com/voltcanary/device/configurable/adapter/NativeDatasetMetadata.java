package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Real native owner metadata: current account authority and verified physical catalog, never request fixtures. */
@RequiredArgsConstructor
public final class NativeDatasetMetadata implements VclMetadata {
    private final DataSource source;
    private final NativeDatasetCatalog catalog;
    private final UUID account;
    private final UUID actor;

    @Override
    public String accessScopeDigest() {
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT updated_at FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE'")) {
            query.setObject(1, account);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
                return VclCanonical.sha256("NATIVE_OWNER/1:" + account + ":" + actor + ":" + rows.getString(1));
            }
        } catch (SQLException error) { throw new VclException("NATIVE_STORAGE_UNAVAILABLE", ""); }
    }

    @Override
    public Optional<Snapshot> dataset(UUID id, long revision) {
        String before = accessScopeDigest();
        var result = catalog.ready(account, id, revision).map(dataset -> new Snapshot(id, revision, dataset.metadata()));
        if (!before.equals(accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        return result;
    }

    @Override public Optional<Snapshot> binding(UUID id, long revision) { return Optional.empty(); }
    @Override public boolean permits(Resource kind, UUID id) { accessScopeDigest(); return false; }
}
