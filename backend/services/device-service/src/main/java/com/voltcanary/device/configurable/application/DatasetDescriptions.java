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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DatasetDescriptions {

    public record Summary(UUID id, long schemaRevision, String label) {}

    public record Capabilities(
        String profile,
        UUID datasetId,
        long schemaRevision,
        UUID generation,
        String sourceMode,
        Set<String> actions,
        java.util.Map<String, String> unavailableReasons
    ) {}

    private final AccountService accounts;
    private final NativeDatasetCatalog catalog;
    private final DataSource source;

    public VclDatasetDescription describe(AuthenticatedUser user, UUID id, long revision) {
        return describe(user.id(), id, revision);
    }

    public Capabilities capabilities(AuthenticatedUser user, UUID id) {
        var dataset = describe(user, id, 0);
        var actions = new java.util.LinkedHashSet<>(
            java.util.List.of(
                "QUERY",
                "INSERT",
                "UPDATE",
                "DELETE",
                "MIGRATE",
                "SHARE",
                "DASHBOARD"
            )
        );
        var reasons = new java.util.LinkedHashMap<String, String>();
        if (!dataset.definition().path("keyFieldIds").isEmpty()) actions.add("IMPORT");
        else reasons.put("IMPORT", "Business keys are required for imports.");
        if (dataset.historyReady()) actions.add("HISTORY");
        else reasons.put("HISTORY", "Recorded history is unavailable for this table.");
        if (
            java.util.stream.StreamSupport.stream(
                dataset.definition().path("fields").spliterator(),
                false
            ).anyMatch(field -> field.path("type").asText().equals("INTERVAL"))
        ) actions.add("TEMPORAL_QUERY");
        else reasons.put("TEMPORAL_QUERY", "An interval field is required for event-time lookup.");
        return new Capabilities(
            "dataset-actions-1",
            id,
            dataset.schemaRevision(),
            dataset.generation(),
            "NATIVE",
            Set.copyOf(actions),
            java.util.Map.copyOf(reasons)
        );
    }

    public VclDatasetDescription describeForAccount(
        UUID actor,
        UUID expectedAccount,
        UUID id,
        long revision
    ) {
        requireAccount(actor, expectedAccount);
        var result = describe(actor, id, revision);
        requireAccount(actor, expectedAccount);
        return result;
    }

    private void requireAccount(UUID actor, UUID expectedAccount) {
        var account = accounts
            .findByUserId(actor)
            .orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (
            !account.getId().equals(expectedAccount) || account.getStatus() != AccountStatus.ACTIVE
        ) {
            throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        }
    }

    public java.util.List<Summary> list(AuthenticatedUser actor) {
        var account = accounts
            .findByUserId(actor.id())
            .orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        var metadata = new NativeDatasetMetadata(source, catalog, account.getId(), actor.id());
        String scope = metadata.accessScopeDigest();
        var result = catalog
            .list(account.getId(), 100)
            .stream()
            .map(dataset ->
                new Summary(
                    dataset.schema().id(),
                    dataset.schema().revision(),
                    dataset.schema().definition().path("label").asText()
                )
            )
            .toList();
        if (!scope.equals(metadata.accessScopeDigest())) throw new VclException(
            "ACCESS_SCOPE_CHANGED",
            ""
        );
        return result;
    }

    public java.util.List<NativeDatasetCatalog.ArchivedTable> archived(AuthenticatedUser actor) {
        var account = accounts
            .findByUserId(actor.id())
            .orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        return catalog.archived(account.getId(), actor.id());
    }

    public VclDatasetDescription describe(UUID actor, UUID id, long revision) {
        var account = accounts
            .findByUserId(actor)
            .orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new VclException(
            "ACCOUNT_ACCESS_REVOKED",
            ""
        );
        var metadata = new NativeDatasetMetadata(source, catalog, account.getId(), actor);
        String before = metadata.accessScopeDigest();
        var dataset = (
            revision == 0
                ? catalog.current(account.getId(), id)
                : catalog.ready(account.getId(), id, revision)
        ).orElseThrow(() -> new VclException("DATASET_UNAVAILABLE", ""));
        boolean historyReady =
            com.voltcanary.device.configurable.adapter.JdbcNativeHistoryRows.ready(source, dataset);
        if (!before.equals(metadata.accessScopeDigest())) throw new VclException(
            "ACCESS_SCOPE_CHANGED",
            ""
        );
        return new VclDatasetDescription(
            id,
            dataset.schema().revision(),
            dataset.metadata(),
            dataset.schema().digest(),
            before,
            dataset.generation(),
            dataset.dataVersion(),
            Set.copyOf(dataset.schema().indexes()),
            "native-dense-1",
            true,
            historyReady,
            com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries.capabilities()
        );
    }
}
