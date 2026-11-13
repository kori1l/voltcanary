package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.analytics.vcl.CompilationBundleStore;
import com.voltcanary.vcl.VclException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Insert-only adapter; duplicate identity can replay identical bytes but cannot overwrite them. */
public final class JdbcCompilationBundleStore implements CompilationBundleStore {
    private final DataSource dataSource;

    public JdbcCompilationBundleStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public CompilationBundle put(CompilationBundle bundle) {
        try (var connection = dataSource.getConnection()) {
            // This adapter owns a single-statement commit; it must not silently join an outer transaction.
            if (!connection.getAutoCommit()) throw new VclException("BUNDLE_AUTOCOMMIT_REQUIRED", "");
            try (var insert = connection.prepareStatement("""
                    INSERT INTO vcl_compilation_bundles(account_id,bundle_id,bundle_version,payload_digest,canonical_payload)
                    VALUES(?,?,?,?,?)
                    """)) {
                insert.setObject(1, bundle.accountId());
                insert.setObject(2, bundle.id());
                insert.setString(3, CompilationBundle.VERSION);
                insert.setString(4, bundle.digest());
                insert.setString(5, bundle.payload());
                insert.executeUpdate();
                return bundle;
            }
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState())) throw new VclException("BUNDLE_STORAGE_UNAVAILABLE", "");
        }
        var existing = find(bundle.accountId(), bundle.id()).orElseThrow(() -> new VclException("BUNDLE_STORAGE_UNAVAILABLE", ""));
        if (!existing.digest().equals(bundle.digest()) || !existing.payload().equals(bundle.payload())) throw new VclException("BUNDLE_ID_REUSED", "");
        return existing;
    }

    @Override
    public Optional<CompilationBundle> find(UUID accountId, UUID bundleId) {
        try (var connection = dataSource.getConnection();
                var query = connection.prepareStatement("""
                        SELECT bundle_version,payload_digest,canonical_payload FROM vcl_compilation_bundles
                        WHERE account_id=? AND bundle_id=?
                        """)) {
            query.setObject(1, accountId);
            query.setObject(2, bundleId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!CompilationBundle.VERSION.equals(rows.getString("bundle_version"))) throw new VclException("BUNDLE_VERSION_UNAVAILABLE", "");
                return Optional.of(CompilationBundle.restore(accountId, bundleId, rows.getString("canonical_payload"), rows.getString("payload_digest")));
            }
        } catch (SQLException error) { throw new VclException("BUNDLE_STORAGE_UNAVAILABLE", ""); }
    }
}
