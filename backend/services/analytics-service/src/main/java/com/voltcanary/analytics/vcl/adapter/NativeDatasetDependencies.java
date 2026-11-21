package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Dependency discovery uses verified typed IR and actual deployment state, never a text search. */
@Repository
@RequiredArgsConstructor
public class NativeDatasetDependencies {
    private final DataSource source;

    public JsonNode inspect(UUID account, UUID dataset, long revision) {
        if (revision < 1) throw new VclException("SCHEMA_REVISION_CONFLICT", "");
        var output = JsonNodeFactory.instance.objectNode().put("owner", "analytics")
                .put("accountId", account.toString()).put("datasetId", dataset.toString())
                .put("schemaRevision", revision).put("complete", true);
        return scan(account, output, bundle -> bundle.dependencies().stream().anyMatch(dependency ->
                dependency.kind() == VclMetadata.Resource.DATASET && dependency.id().equals(dataset)
                        && dependency.revision() == revision));
    }

    JsonNode inspectCircle(UUID account, UUID circle, long revision) {
        var output = JsonNodeFactory.instance.objectNode().put("owner", "analytics")
                .put("circleId", circle.toString()).put("revision", revision).put("complete", true);
        return scan(account, output, bundle -> {
            for (var pin : bundle.json().at("/ownerValidation/execution/resourceRevisions")) {
                if ("SPATIAL_FEATURE".equals(pin.path("kind").textValue())
                        && circle.toString().equals(pin.path("id").textValue())
                        && revision == pin.path("revision").longValue()) return true;
            }
            return false;
        });
    }

    private JsonNode scan(UUID account, ObjectNode output, Predicate<CompilationBundle> matches) {
        var dependencies = output.putArray("dependencies");
        long bytes = 0;
        int count = 0;
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var query = connection.prepareStatement("""
                    SELECT b.bundle_id,b.payload_digest,b.bundle_version,
                        CASE WHEN OCTET_LENGTH(b.canonical_payload)<=8388608 THEN b.canonical_payload ELSE NULL END,
                        d.deployment_epoch,d.rule_id,d.state,d.revision,d.bundle_digest
                    FROM vcl_compilation_bundles b LEFT JOIN vcl_rule_deployments d
                        ON d.account_id=b.account_id AND d.bundle_id=b.bundle_id AND d.state IN ('ACTIVE','STAGED')
                    WHERE b.account_id=? ORDER BY b.bundle_id,d.deployment_epoch LIMIT 501
                    """)) {
                query.setObject(1, account);
                query.setQueryTimeout(2);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++count > 500) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        String payload = rows.getString(4);
                        if (payload == null || !CompilationBundle.VERSION.equals(rows.getString(3))) throw new VclException("BUNDLE_INTEGRITY", "");
                        bytes += payload.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > 32 * 1_048_576L) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        UUID bundleId = rows.getObject(1, UUID.class);
                        var bundle = CompilationBundle.restore(account, bundleId, payload, rows.getString(2));
                        if (!matches.test(bundle)) continue;
                        UUID deployment = rows.getObject(5, UUID.class);
                        var dependency = dependencies.addObject().put("id", (deployment == null ? bundleId : deployment).toString())
                                .put("kind", deployment == null ? "RULE_BUNDLE" : "RULE_DEPLOYMENT")
                                .put("bundleId", bundleId.toString()).put("bundleDigest", bundle.digest()).put("blocking", deployment != null);
                        if (deployment != null) {
                            if (!bundle.digest().equals(rows.getString(9))) throw new VclException("BUNDLE_INTEGRITY", "");
                            dependency.put("ruleId", rows.getObject(6, UUID.class).toString())
                                    .put("state", rows.getString(7)).put("revision", Long.toString(rows.getLong(8)));
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException unavailable) {
            throw new VclException("DEPENDENCY_OWNER_UNAVAILABLE", "");
        }
        output.put("cutDigest", VclCanonical.sha256(VclCanonical.encode(dependencies)));
        return output;
    }
}
