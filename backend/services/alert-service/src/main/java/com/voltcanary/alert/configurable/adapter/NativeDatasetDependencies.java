package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NativeDatasetDependencies {
    private final DataSource source;

    public JsonNode inspect(UUID account, UUID dataset, long revision) {
        if (revision < 1) throw new VclException("SCHEMA_REVISION_CONFLICT", "");
        var output = JsonNodeFactory.instance.objectNode().put("owner", "alerts")
                .put("accountId", account.toString()).put("datasetId", dataset.toString())
                .put("schemaRevision", revision).put("complete", true);
        var dependencies = output.putArray("dependencies");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            var now = GrantSql.now(connection).atOffset(ZoneOffset.UTC);
            try (var query = connection.prepareStatement("""
                    SELECT id,rule_id,epoch,definition_digest,
                        CASE WHEN OCTET_LENGTH(canonical_definition)<=1048576 THEN canonical_definition ELSE NULL END
                    FROM cfg_automation_grant WHERE account_id=? AND status='ACTIVE' AND expires_at>?
                    ORDER BY id LIMIT 501
                    """)) {
                query.setObject(1, account);
                query.setObject(2, now);
                query.setQueryTimeout(2);
                int scanned = 0;
                long bytes = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++scanned > 500) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        String definition = rows.getString(5);
                        if (definition == null || !VclCanonical.sha256(definition).equals(rows.getString(4))) {
                            throw new VclException("GRANT_STATE_CORRUPT", "");
                        }
                        bytes += definition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (bytes > 8 * 1_048_576L) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        var scope = AutomationGrantScope.parse(GrantJson.read(definition));
                        if (scope.datasets().stream().noneMatch(target -> target.id().equals(dataset) && target.schemaRevision() == revision)) continue;
                        dependencies.addObject().put("id", rows.getObject(1, UUID.class).toString()).put("kind", "AUTOMATION_GRANT")
                                .put("ruleId", rows.getObject(2, UUID.class).toString()).put("epoch", Long.toString(rows.getLong(3)))
                                .put("definitionDigest", rows.getString(4)).put("blocking", true);
                    }
                }
            }
            try (var query = connection.prepareStatement("""
                    SELECT id,rule_id,revision,status,review_digest,
                        CASE WHEN OCTET_LENGTH(canonical_definition)<=1048576 THEN canonical_definition ELSE NULL END
                    FROM cfg_grant_review WHERE account_id=? AND status IN ('PREPARED','APPLYING','UNKNOWN')
                        AND expires_at>? ORDER BY id LIMIT 501
                    """)) {
                query.setObject(1, account);
                query.setObject(2, now);
                query.setQueryTimeout(2);
                int scanned = 0;
                long bytes = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++scanned > 500) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        String definition = rows.getString(6);
                        if (definition == null) throw new VclException("GRANT_STATE_CORRUPT", "");
                        bytes += definition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (bytes > 8 * 1_048_576L) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        var scope = AutomationGrantScope.parse(GrantJson.read(definition));
                        if (scope.datasets().stream().noneMatch(target -> target.id().equals(dataset) && target.schemaRevision() == revision)) continue;
                        if (dependencies.size() >= 500) throw new VclException("DEPENDENCY_SCAN_LIMIT", "");
                        dependencies.addObject().put("id", rows.getObject(1, UUID.class).toString()).put("kind", "GRANT_REVIEW")
                                .put("ruleId", rows.getObject(2, UUID.class).toString()).put("revision", Long.toString(rows.getLong(3)))
                                .put("state", rows.getString(4)).put("reviewDigest", rows.getString(5))
                                .put("blocking", !rows.getString(4).equals("PREPARED"));
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
