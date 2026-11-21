package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Static front end over owner-authorized immutable metadata snapshots; no SQL/network/runtime effects. */
public final class VclCompiler {
    private static final VclParser MAPPINGS = new VclParser();
    private final VclRegistry registry;
    private final VclUnits units;

    public VclCompiler(VclRegistry registry, VclUnits units) {
        this.registry = registry;
        this.units = units;
    }

    public VclCompilation compile(VclDocument document, VclMetadata metadata) {
        VclException.require(metadata != null, "ACCESS_SCOPE_REQUIRED", "");
        String scopeDigest = metadata.accessScopeDigest();
        VclException.require(scopeDigest != null && scopeDigest.matches("[0-9a-f]{64}"), "ACCESS_SCOPE_REQUIRED", "");
        JsonNode spec = VclValues.normalized(document.spec());
        var session = new Session(metadata);
        List<VclCompilation.Node> graph = switch (document.kind()) {
            case COLLECTION -> { session.collection(spec); yield List.of(); }
            case QUERY -> { session.query(spec); yield List.of(); }
            case RULE -> new VclGraphCompiler(registry, units, session).compile(spec);
            case MUTATION -> { session.mutation(spec); yield List.of(); }
            case IMPORT -> { new VclImportChecks(units, session).check(spec); yield List.of(); }
            case CONNECTION -> { session.connection(spec); yield List.of(); }
        };
        VclException.require(scopeDigest.equals(metadata.accessScopeDigest()), "ACCESS_SCOPE_CHANGED", "");
        return new VclCompilation(document.kind(), VclCanonical.json(document), VclCanonical.documentDigest(document), VclCanonical.semanticDigest(document),
                "vcl-1-spec-2", "vcl-frontend-1", registry.digest(), units.digest(), scopeDigest, List.copyOf(session.dependencies.values()), graph);
    }

    /** Type-check one closed registered mapping without inventing an uploaded artifact or runtime authority. */
    public List<VclCompilation.Dependency> checkImportColumn(JsonNode column, VclType source, JsonNode targetField, VclMetadata metadata) {
        var checked = MAPPINGS.importColumn(column.toString());
        VclException.require(metadata != null && metadata.accessScopeDigest() != null
                && metadata.accessScopeDigest().matches("[0-9a-f]{64}"), "ACCESS_SCOPE_REQUIRED", "");
        String scope = metadata.accessScopeDigest();
        VclException.require(checked.path("targetFieldId").asText().equals(targetField.path("id").asText()), "FIELD_NOT_IN_SCOPE", "");
        var session = new Session(metadata);
        new VclImportChecks(units, session).column(checked, source, VclType.from(targetField));
        VclException.require(scope.equals(metadata.accessScopeDigest()), "ACCESS_SCOPE_CHANGED", "");
        return List.copyOf(session.dependencies.values());
    }

    final class Session {
        final VclMetadata metadata;
        final Map<String, VclCompilation.Dependency> dependencies = new LinkedHashMap<>();
        final VclExpressionTypes expressions;

        Session(VclMetadata metadata) {
            this.metadata = metadata;
            expressions = new VclExpressionTypes(registry, units, id -> {
                permit(VclMetadata.Resource.REFERENCE, id.toString(), "/literal");
                return metadata.referenceType(id).orElseThrow(() -> new VclException("REF_TYPE_UNAVAILABLE", "/literal"));
            });
        }

        JsonNode dataset(JsonNode target, String path) {
            UUID id = UUID.fromString(target.path("datasetId").asText());
            long revision = target.path("schemaRevision").longValue();
            var snapshot = metadata.dataset(id, revision).orElseThrow(() -> new VclException("SCHEMA_UNAVAILABLE", path));
            VclException.require(snapshot.id().equals(id) && snapshot.revision() == revision, "SCHEMA_UNAVAILABLE", path);
            depend(VclMetadata.Resource.DATASET, snapshot);
            return VclValues.normalized(snapshot.definition());
        }

        JsonNode binding(JsonNode config, String path) {
            UUID id = UUID.fromString(config.path("bindingId").asText());
            long revision = config.path("schemaRevision").longValue();
            var snapshot = metadata.binding(id, revision).orElseThrow(() -> new VclException("BINDING_UNAVAILABLE", path));
            VclException.require(snapshot.id().equals(id) && snapshot.revision() == revision, "BINDING_UNAVAILABLE", path);
            depend(VclMetadata.Resource.BINDING, snapshot);
            return VclValues.normalized(snapshot.definition());
        }

        void depend(VclMetadata.Resource kind, VclMetadata.Snapshot snapshot) {
            var dependency = new VclCompilation.Dependency(kind, snapshot.id(), snapshot.revision(),
                    VclCanonical.sha256(VclCanonical.encode(snapshot.definition())));
            String key = kind + ":" + snapshot.id();
            var prior = dependencies.putIfAbsent(key, dependency);
            VclException.require(prior == null || prior.equals(dependency), "METADATA_SNAPSHOT_CHANGED", "");
        }

        void permit(VclMetadata.Resource kind, String id, String path) {
            UUID value = UUID.fromString(id);
            VclException.require(metadata.permits(kind, value), "RESOURCE_UNAVAILABLE", path);
        }

        Map<String, VclType> fields(JsonNode fields, String path) {
            var result = new LinkedHashMap<String, VclType>();
            for (var field : fields) {
                String id = field.path("id").asText().toLowerCase();
                var type = VclType.from(field);
                VclException.require(type.kind() != VclType.Kind.NULL, "INVALID_FIELD_TYPE", path);
                if (type.numeric()) units.require(type.unit(), path);
                if (field.has("scale")) VclException.require(field.path("scale").asInt() <= field.path("precision").asInt(),
                        "PRECISION_LOSS", path);
                VclException.require(result.putIfAbsent(id, type) == null, "DUPLICATE_FIELD", path);
            }
            return Map.copyOf(result);
        }

        void collection(JsonNode spec) {
            var fields = fields(spec.path("fields"), "/spec/fields");
            for (var key : spec.path("keyFieldIds")) {
                var type = VclExpressionTypes.field(fields, key.asText(), "/spec/keyFieldIds");
                VclException.require(!type.nullable() && Set.of(VclType.Kind.TEXT, VclType.Kind.ENUM, VclType.Kind.INT64,
                        VclType.Kind.DATE, VclType.Kind.INSTANT, VclType.Kind.REF).contains(type.kind()), "INVALID_KEY_FIELD", "/spec/keyFieldIds");
            }
            for (var id : spec.at("/storage/indexedFieldIds")) VclExpressionTypes.field(fields, id.asText(), "/spec/storage/indexedFieldIds");
            if (spec.at("/storage/mode").asText().equals("EXTERNAL")) {
                permit(VclMetadata.Resource.CONNECTION, spec.at("/storage/connectionId").asText(), "/spec/storage");
            }
        }

        void query(JsonNode spec) {
            var dataset = dataset(spec.path("target"), "/spec/target");
            var scope = datasetFields(dataset);
            for (var id : spec.path("select")) VclExpressionTypes.field(scope, id.asText(), "/spec/select");
            for (var sort : spec.path("order")) VclExpressionTypes.field(scope, sort.path("fieldId").asText(), "/spec/order");
            expressions.predicate(spec.path("where"), scope, "/spec/where");
        }

        void mutation(JsonNode spec) {
            var dataset = dataset(spec.path("target"), "/spec/target");
            var scope = datasetFields(dataset);
            precondition(dataset, spec.path("targetPrecondition"));
            var keys = unique(spec.path("key"), "fieldId", "/spec/key");
            unique(spec.path("values"), "fieldId", "/spec/values");
            var expected = strings(dataset.path("keyFieldIds"));
            if (expected.isEmpty() && !spec.path("operation").asText().equals("INSERT")) {
                String recordId = dataset.at("/systemFields/recordIdFieldId").asText();
                VclException.require(!recordId.isEmpty(), "SYSTEM_KEY_UNAVAILABLE", "/spec/key");
                expected = List.of(recordId);
            }
            VclException.require(strings(spec.path("key"), "fieldId").equals(expected), "UNIQUE_TARGET_KEY_REQUIRED", "/spec/key");
            for (var key : spec.path("key")) {
                VclException.require(!key.at("/literal/type").asText().equals("NULL"), "NULL_KEY", "/spec/key");
                assign(dataset, scope, key, "/spec/key");
            }
            for (var value : spec.path("values")) {
                VclException.require(!keys.contains(value.path("fieldId").asText()), "MUTATION_IMMUTABLE_KEY", "/spec/values");
                VclException.require(!value.path("fieldId").asText().equals(dataset.at("/systemFields/recordIdFieldId").asText()),
                        "MUTATION_IMMUTABLE_KEY", "/spec/values");
                assign(dataset, scope, value, "/spec/values");
            }
            if (dataset.at("/storage/mode").asText().equals("NATIVE") && !spec.path("expectedRowVersion").isNull()) {
                VclGraphCompiler.duration(spec.path("expectedRowVersion"), "/spec/expectedRowVersion");
            }
            if (spec.path("operation").asText().equals("INSERT")) {
                var supplied = new LinkedHashSet<>(keys);
                supplied.addAll(unique(spec.path("values"), "fieldId", "/spec/values"));
                fields(dataset.path("fields"), "/spec/target").forEach((id, type) ->
                        VclException.require(type.nullable() || supplied.contains(id), "REQUIRED_FIELD_MISSING", "/spec/values"));
            }
            if (dataset.at("/storage/mode").asText().equals("EXTERNAL")) {
                permit(VclMetadata.Resource.ADAPTER, spec.at("/targetPrecondition/adapterContractId").asText(), "/spec/targetPrecondition");
            }
        }

        void assign(JsonNode dataset, Map<String, VclType> scope, JsonNode pair, String path) {
            String id = pair.path("fieldId").asText();
            var target = VclExpressionTypes.field(scope, id, path);
            var literal = pair.path("literal");
            if (literal.path("type").asText().equals("NULL")) {
                VclException.require(target.nullable(), "NULL_NOT_ALLOWED", path);
                return;
            }
            target.compatible(VclType.from(literal), path);
            if (target.kind() == VclType.Kind.REF) permit(VclMetadata.Resource.REFERENCE, literal.path("value").asText(), path);
            if (id.equals(dataset.at("/systemFields/recordIdFieldId").asText())) return;
            var definition = java.util.stream.StreamSupport.stream(dataset.path("fields").spliterator(), false)
                    .filter(f -> f.path("id").asText().equalsIgnoreCase(id)).findFirst().orElseThrow();
            if (target.kind() == VclType.Kind.TEXT && definition.has("maxLength")) {
                String text = literal.path("value").asText();
                VclException.require(text.codePointCount(0, text.length()) <= definition.path("maxLength").asInt(), "TEXT_LIMIT", path);
            }
            if (target.kind() == VclType.Kind.ENUM) VclException.require(strings(definition.path("values")).contains(literal.path("value").asText()),
                    "ENUM_VALUE_INVALID", path);
            if (target.kind() == VclType.Kind.DECIMAL && definition.has("precision")) {
                var number = new java.math.BigDecimal(literal.path("value").asText());
                VclException.require(Math.max(0, number.scale()) <= definition.path("scale").asInt()
                        && number.setScale(definition.path("scale").asInt()).precision() <= definition.path("precision").asInt(), "PRECISION_LOSS", path);
            }
        }

        Map<String, VclType> datasetFields(JsonNode dataset) {
            var scope = new LinkedHashMap<>(fields(dataset.path("fields"), "/spec/target"));
            String id = dataset.at("/systemFields/recordIdFieldId").asText();
            if (!id.isEmpty() && dataset.at("/storage/mode").asText().equals("NATIVE")) {
                VclException.require(!scope.containsKey(id), "FIELD_SCOPE_COLLISION", "/spec/target");
                scope.put(id, new VclType(VclType.Kind.REF, false, "1", "DATASET:" + dataset.path("id").asText()));
            }
            return Map.copyOf(scope);
        }

        void precondition(JsonNode dataset, JsonNode precondition) {
            String mode = dataset.at("/storage/mode").asText();
            VclException.require(mode.equals(precondition.path("mode").asText()) && !mode.equals("PROJECTED"), "TARGET_MODE_MISMATCH", "/spec/targetPrecondition");
            if (mode.equals("NATIVE") && dataset.has("generation")) {
                VclException.require(dataset.path("generation").equals(precondition.path("generation"))
                        && dataset.path("dataVersion").longValue() == precondition.path("dataVersion").longValue(), "TARGET_CONFLICT", "/spec/targetPrecondition");
            }
            if (mode.equals("EXTERNAL")) VclException.require(dataset.at("/storage/schemaFingerprint").equals(precondition.path("schemaFingerprint")),
                    "SCHEMA_DRIFT", "/spec/targetPrecondition/schemaFingerprint");
        }

        void connection(JsonNode spec) {
            permit(VclMetadata.Resource.ENDPOINT, spec.path("endpointRef").asText(), "/spec/endpointRef");
            permit(VclMetadata.Resource.SECRET, spec.path("secretRef").asText(), "/spec/secretRef");
        }
    }

    static Set<String> unique(JsonNode items, String property, String path) {
        var result = new LinkedHashSet<String>();
        for (var item : items) VclException.require(result.add(item.path(property).asText()), "DUPLICATE_FIELD", path);
        return result;
    }

    static List<String> strings(JsonNode items) {
        var result = new ArrayList<String>();
        items.forEach(i -> result.add(i.asText()));
        return List.copyOf(result);
    }

    static List<String> strings(JsonNode items, String field) {
        var result = new ArrayList<String>();
        items.forEach(i -> result.add(i.path(field).asText()));
        return List.copyOf(result);
    }
}
