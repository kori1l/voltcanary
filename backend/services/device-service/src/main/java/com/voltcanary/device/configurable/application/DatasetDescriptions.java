package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.adapter.NativeDatasetMetadata;
import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

@Service
public class DatasetDescriptions {
    public record Summary(UUID id, long schemaRevision, String label) { }
    private final AccountService accounts;
    private final NativeDatasetCatalog catalog;
    private final DataSource source;

    public DatasetDescriptions(AccountService accounts, NativeDatasetCatalog catalog, DataSource source) {
        this.accounts = accounts;
        this.catalog = catalog;
        this.source = source;
    }

    public VclDatasetDescription describe(AuthenticatedUser user, UUID id, long revision) {
        return describe(user.id(), id, revision);
    }

    public VclDatasetDescription describeForAccount(UUID actor, UUID expectedAccount, UUID id, long revision) {
        requireAccount(actor, expectedAccount);
        var result = describe(actor, id, revision);
        requireAccount(actor, expectedAccount);
        return result;
    }

    private void requireAccount(UUID actor, UUID expectedAccount) {
        var account = accounts.findByUserId(actor).orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (!account.getId().equals(expectedAccount) || account.getStatus() != AccountStatus.ACTIVE) {
            throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        }
    }

    public java.util.List<Summary> list(AuthenticatedUser actor) {
        var account = accounts.findByUserId(actor.id()).orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        var metadata = new NativeDatasetMetadata(source, catalog, account.getId(), actor.id());
        String scope = metadata.accessScopeDigest();
        var result = catalog.list(account.getId(), 100).stream().map(dataset -> new Summary(
                dataset.schema().id(), dataset.schema().revision(), dataset.schema().definition().path("label").asText())).toList();
        if (!scope.equals(metadata.accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        return result;
    }

    public VclDatasetDescription describe(UUID actor, UUID id, long revision) {
        var account = accounts.findByUserId(actor).orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        var metadata = new NativeDatasetMetadata(source, catalog, account.getId(), actor);
        String before = metadata.accessScopeDigest();
        var dataset = catalog.ready(account.getId(), id, revision).orElseThrow(() -> new VclException("DATASET_UNAVAILABLE", ""));
        if (!before.equals(metadata.accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        return new VclDatasetDescription(id, revision, dataset.metadata(), dataset.schema().digest(), before,
                dataset.generation(), dataset.dataVersion(), Set.copyOf(dataset.schema().indexes()), "native-dense-1", true, false,
                com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries.capabilities());
    }
}
