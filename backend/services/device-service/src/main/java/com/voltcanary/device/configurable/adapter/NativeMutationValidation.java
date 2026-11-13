package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

final class NativeMutationValidation {
    static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    private NativeMutationValidation() { }

    static String compile(Connection connection, NativeDataset dataset, String scope, VclDocument document, UUID createdId) throws SQLException {
        if (document.kind() != VclDocument.Kind.MUTATION) throw new VclException("MUTATION_REQUIRED", "");
        var references = new LinkedHashMap<UUID, VclType>();
        for (String group : java.util.List.of("key", "values")) {
            for (var pair : document.spec().path(group)) {
                UUID fieldId = UUID.fromString(pair.path("fieldId").asText());
                if (pair.at("/literal/type").asText().equals("REF")) {
                    UUID value = UUID.fromString(pair.at("/literal/value").asText());
                    VclType type;
                    if (fieldId.equals(dataset.recordIdFieldId())) {
                        if (!record(connection, dataset, value)) throw new VclException("ROW_UNAVAILABLE", "");
                        type = new VclType(VclType.Kind.REF, false, "1", "DATASET:" + dataset.schema().id());
                    } else {
                        var field = dataset.schema().field(fieldId);
                        type = VclType.from(field);
                        if (type.kind() != VclType.Kind.REF) throw new VclException("TYPE_MISMATCH", "");
                        authorize(connection, dataset, field.path("target"), value, createdId, document.spec().path("operation").asText());
                    }
                    var previous = references.putIfAbsent(value, type);
                    if (previous != null) previous.compatible(type, "");
                }
            }
        }
        var metadata = new VclMetadata() {
            public String accessScopeDigest() { return scope; }
            public Optional<Snapshot> dataset(UUID id, long revision) {
                return id.equals(dataset.schema().id()) && revision == dataset.schema().revision()
                        ? Optional.of(new Snapshot(id, revision, dataset.metadata())) : Optional.empty();
            }
            public Optional<Snapshot> binding(UUID id, long revision) { return Optional.empty(); }
            public boolean permits(Resource kind, UUID id) { return kind == Resource.REFERENCE && references.containsKey(id); }
            public Optional<VclType> referenceType(UUID id) { return Optional.ofNullable(references.get(id)); }
        };
        String canonical = COMPILER.compile(document, metadata).canonicalDocument();
        var spec = PARSER.parse(canonical).spec();
        for (String group : java.util.List.of("key", "values")) {
            for (var pair : spec.path(group)) {
                UUID id = UUID.fromString(pair.path("fieldId").asText());
                if (!id.equals(dataset.recordIdFieldId())) NativeRowCodec.encode(dataset.schema(), id, pair.path("literal"));
            }
        }
        if (!spec.path("expectedRowVersion").isNull() && Long.parseLong(spec.path("expectedRowVersion").asText()) <= 0) {
            throw new VclException("ROW_VERSION_INVALID", "");
        }
        return canonical;
    }

    private static void authorize(Connection connection, NativeDataset dataset, JsonNode target, UUID id, UUID createdId, String operation) throws SQLException {
        if (target.path("kind").asText().equals("DATASET")) {
            UUID targetId = UUID.fromString(target.path("datasetId").asText());
            if (targetId.equals(dataset.schema().id()) && operation.equals("INSERT") && id.equals(createdId)) return;
            var destination = JdbcNativeDatasetCatalog.definition(connection, dataset.accountId(), targetId, 1, true)
                    .orElseThrow(() -> new VclException("REFERENCE_TARGET_UNAVAILABLE", ""));
            if (!record(connection, destination, id)) throw new VclException("REFERENCE_TARGET_UNAVAILABLE", "");
            return;
        }
        String sql = switch (target.path("resourceKind").asText()) {
            case "DEVICE" -> "SELECT 1 FROM devices WHERE account_id=? AND id=? AND status<>'DECOMMISSIONED'";
            case "SOURCE" -> "SELECT 1 FROM data_sources s JOIN devices d ON d.id=s.device_id WHERE d.account_id=? AND s.id=? AND s.status<>'REVOKED' AND d.status<>'DECOMMISSIONED'";
            default -> throw new VclException("REFERENCE_TARGET_UNAVAILABLE", "");
        };
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, dataset.accountId());
            query.setObject(2, id);
            try (var rows = query.executeQuery()) { if (!rows.next()) throw new VclException("REFERENCE_TARGET_UNAVAILABLE", ""); }
        }
    }

    static boolean record(Connection connection, NativeDataset dataset, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(dataset.schema()).table()) + " WHERE record_id=?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }
}
