package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Inspects binding/query declarations only. Presentation text and extension payloads are never references. */
@Repository
@RequiredArgsConstructor
public class NativeDatasetDependencies {
    private final DataSource source;
    private final ObjectMapper mapper;

    public JsonNode inspect(UUID account, UUID dataset, long revision) {
        if (revision < 1) throw new DashboardFailure("VALIDATION_ERROR", "Schema revision must be positive");
        var output = mapper.createObjectNode().put("owner", "dashboards")
                .put("accountId", account.toString()).put("datasetId", dataset.toString())
                .put("schemaRevision", revision).put("complete", true);
        return scan(account, output, widget -> {
            var document = widget.path("query");
            var target = document.path("language").asText().equals("VCL/1")
                    && document.path("kind").asText().equals("QUERY") ? document.at("/spec/target") : widget.path("binding");
            return target.path("datasetId").asText().equals(dataset.toString())
                    && target.path("schemaRevision").isIntegralNumber() && target.path("schemaRevision").longValue() == revision;
        });
    }

    private JsonNode scan(UUID account, ObjectNode output, Predicate<JsonNode> matches) {
        var dependencies = output.putArray("dependencies");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var query = connection.prepareStatement("""
                    SELECT d.id,TRUE AS published,CAST(r.revision AS VARCHAR) AS version,
                        CASE WHEN OCTET_LENGTH(CAST(r.definition AS VARCHAR))<=1048576 THEN CAST(r.definition AS VARCHAR) ELSE NULL END AS definition
                    FROM dashboards d JOIN dashboard_revisions r ON r.dashboard_id=d.id AND r.revision=d.published_revision
                    WHERE d.account_id=? AND d.status='ACTIVE'
                    UNION ALL
                    SELECT d.id,FALSE AS published,CAST(v.etag AS VARCHAR) AS version,
                        CASE WHEN OCTET_LENGTH(CAST(v.definition AS VARCHAR))<=1048576 THEN CAST(v.definition AS VARCHAR) ELSE NULL END AS definition
                    FROM dashboards d JOIN dashboard_drafts v ON v.dashboard_id=d.id
                    WHERE d.account_id=? AND d.status='ACTIVE'
                    ORDER BY 1,2 LIMIT 501
                    """)) {
                query.setObject(1, account);
                query.setObject(2, account);
                query.setQueryTimeout(2);
                int scanned = 0;
                long bytes = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++scanned > 500) throw unavailable();
                        String payload = rows.getString(4);
                        if (payload == null) throw unavailable();
                        bytes += payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (bytes > 8 * 1_048_576L) throw unavailable();
                        JsonNode definition = mapper.readTree(payload);
                        if (definition == null || !definition.isObject() || !definition.path("widgets").isArray()) throw unavailable();
                        var panels = mapper.createArrayNode();
                        for (var widget : definition.path("widgets")) {
                            if (matches.test(widget)) panels.add(widget.path("id").asText());
                        }
                        if (panels.isEmpty()) continue;
                        var dependency = dependencies.addObject().put("id", rows.getObject(1, UUID.class).toString())
                                .put("kind", rows.getBoolean(2) ? "PUBLISHED_DASHBOARD" : "DASHBOARD_DRAFT")
                                .put("revision", rows.getString(3));
                        dependency.put("definitionDigest", QueryHash.of(mapper, definition)).put("blocking", rows.getBoolean(2));
                    }
                }
            }
            connection.commit();
        } catch (SQLException | java.io.IOException invalid) {
            throw unavailable();
        }
        output.put("cutDigest", QueryHash.of(mapper, dependencies));
        return output;
    }

    private static DashboardFailure unavailable() {
        return new DashboardFailure("DEPENDENCY_OWNER_UNAVAILABLE", "Complete dashboard dependency observation is unavailable");
    }
}
