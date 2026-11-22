package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeImportInput;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-bound, batch-resolved identities. Caller JSON never supplies authorization evidence. */
final class NativeImportReferences implements NativeImportInput.ReferenceResolver {

    private record Key(UUID dataset, String tuple, String hash) {}

    private record Resolution(UUID id, long version, String failure) {}

    private static final class Pending extends RuntimeException {}

    private final Map<UUID, NativeDataset> owners;
    private final Map<Key, Resolution> resolved = new LinkedHashMap<>();
    private final Map<Key, Boolean> pending = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, Long>> permitted = new HashMap<>();

    private NativeImportReferences(Map<UUID, NativeDataset> owners) {
        this.owners = Map.copyOf(owners);
    }

    static Map<UUID, NativeDataset> owners(
        Connection connection,
        NativeDataset target,
        JsonNode spec
    ) throws SQLException {
        var requested = new java.util.TreeMap<UUID, Long>();
        for (var column : spec.path("columns"))
            for (var conversion : column.path("conversions")) {
                if (!conversion.path("method").asText().equals("resolve_ref")) continue;
                UUID id = UUID.fromString(conversion.at("/target/datasetId").asText());
                long revision = conversion.at("/target/schemaRevision").longValue();
                Long previous = requested.putIfAbsent(id, revision);
                require(previous == null || previous == revision, "METADATA_SNAPSHOT_CHANGED");
                require(requested.size() <= 32, "REFERENCE_CHECK_LIMIT");
            }
        var result = new LinkedHashMap<UUID, NativeDataset>();
        for (var request : requested.entrySet()) {
            var owner = request.getKey().equals(target.schema().id())
                ? target
                : NativeRowTransactions.head(
                      connection,
                      target.accountId(),
                      request.getKey(),
                      request.getValue()
                  );
            require(owner.schema().revision() == request.getValue(), "SCHEMA_UNAVAILABLE");
            result.put(request.getKey(), owner);
        }
        return Map.copyOf(result);
    }

    static NativeImportReferences prepare(
        Connection connection,
        Map<UUID, NativeDataset> owners,
        List<JsonNode> input,
        JsonNode columns
    ) throws SQLException {
        var references = new NativeImportReferences(owners);
        if (owners.isEmpty()) return references;
        // Each pass advances at least one unresolved conversion per column. Grammar caps chains at four.
        for (int pass = 0; pass <= 4; pass++) {
            references.pending.clear();
            for (var row : input)
                for (var column : columns) {
                    if (
                        java.util.stream.StreamSupport.stream(
                            column.path("conversions").spliterator(),
                            false
                        ).noneMatch(conversion ->
                            conversion.path("method").asText().equals("resolve_ref")
                        )
                    ) continue;
                    try {
                        NativeImportInput.convert(row, column, references);
                    } catch (Pending notResolvedYet) {
                    } catch (VclException invalidRow) {
                        /* The ordinary row pass records safe complete error counts. */
                    }
                }
            if (references.pending.isEmpty()) return references;
            require(
                pass < 4 && references.resolved.size() + references.pending.size() <= 2048,
                "REFERENCE_CHECK_LIMIT"
            );
            references.load(connection, new ArrayList<>(references.pending.keySet()));
        }
        throw new VclException("REFERENCE_CHECK_LIMIT", "");
    }

    @Override
    public JsonNode resolve(JsonNode literal, JsonNode conversion) {
        UUID id = UUID.fromString(conversion.at("/target/datasetId").asText());
        var owner = owners.get(id);
        require(owner != null, "REFERENCE_TARGET_UNAVAILABLE");
        require(
            owner
                .schema()
                .keys()
                .equals(List.of(UUID.fromString(conversion.path("keyFieldId").asText()))),
            "REF_AMBIGUOUS"
        );
        String tuple = NativeRowCodec.keyTuple(
            owner.schema(),
            Map.of(owner.schema().keys().getFirst(), literal)
        );
        var key = new Key(id, tuple, VclCanonical.sha256(tuple));
        var found = resolved.get(key);
        if (found == null) {
            pending.put(key, true);
            throw new Pending();
        }
        require(
            found.failure() == null,
            found.failure() == null ? "REF_NOT_FOUND" : found.failure()
        );
        return JsonNodeFactory.instance
            .objectNode()
            .put("type", "REF")
            .put("value", found.id().toString());
    }

    boolean permits(JsonNode target, UUID id) {
        if (!target.path("kind").asText().equals("DATASET")) return false;
        var rows = permitted.get(UUID.fromString(target.path("datasetId").asText()));
        return rows != null && rows.containsKey(id);
    }

    private void load(Connection connection, List<Key> keys) throws SQLException {
        var groups = new LinkedHashMap<UUID, List<Key>>();
        for (var key : keys)
            groups.computeIfAbsent(key.dataset(), ignored -> new ArrayList<>()).add(key);
        for (var group : groups.entrySet()) {
            var owner = owners.get(group.getKey());
            for (int start = 0; start < group.getValue().size(); start += 500) {
                List<Key> batch = group
                    .getValue()
                    .subList(start, Math.min(start + 500, group.getValue().size()));
                var byHash = new HashMap<String, Key>();
                for (var key : batch) {
                    var previous = byHash.putIfAbsent(key.hash(), key);
                    require(previous == null || previous.equals(key), "KEY_IDENTITY_COLLISION");
                }
                var selected = new ArrayList<String>(
                    List.of("record_id", "record_version", "external_key")
                );
                for (UUID field : owner.schema().keys())
                    selected.addAll(NativeRowCodec.columns(owner, field));
                String sql =
                    "SELECT " +
                    selected
                        .stream()
                        .map(NativeSqlLayout::identifier)
                        .collect(java.util.stream.Collectors.joining(",")) +
                    " FROM " +
                    NativeSqlLayout.identifier(new NativeSqlLayout(owner).table()) +
                    " WHERE external_key IN (" +
                    String.join(",", java.util.Collections.nCopies(batch.size(), "?")) +
                    ")";
                try (var query = connection.prepareStatement(sql)) {
                    for (int i = 0; i < batch.size(); i++) query.setString(
                        i + 1,
                        batch.get(i).hash()
                    );
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            var row = NativeRowCodec.read(rows, owner, owner.schema().keys());
                            var key = byHash.get(rows.getString("external_key"));
                            require(
                                key != null &&
                                    key
                                        .tuple()
                                        .equals(
                                            NativeRowCodec.keyTuple(owner.schema(), row.values())
                                        ),
                                "KEY_IDENTITY_COLLISION"
                            );
                            var previous = resolved.putIfAbsent(
                                key,
                                new Resolution(
                                    row.recordId(),
                                    Long.parseLong(row.recordVersion()),
                                    null
                                )
                            );
                            if (previous != null) resolved.put(
                                key,
                                new Resolution(null, 0, "REF_AMBIGUOUS")
                            );
                        }
                    }
                }
                for (var key : batch) {
                    var result = resolved.computeIfAbsent(key, ignored ->
                        new Resolution(null, 0, "REF_NOT_FOUND")
                    );
                    if (result.failure() == null) permitted
                        .computeIfAbsent(key.dataset(), ignored -> new HashMap<>())
                        .put(result.id(), result.version());
                }
            }
        }
    }

    JsonNode manifest() {
        var result = JsonNodeFactory.instance.arrayNode();
        for (var entry : owners.entrySet()) {
            var selected = resolved
                .entrySet()
                .stream()
                .filter(
                    row ->
                        row.getKey().dataset().equals(entry.getKey()) &&
                        row.getValue().failure() == null
                )
                .toList();
            var owner = entry.getValue();
            var pin = result
                .addObject()
                .put("datasetId", entry.getKey().toString())
                .put("schemaRevision", owner.schema().revision())
                .put("schemaDigest", owner.schema().digest())
                .put("generation", owner.generation().toString())
                .put("dataVersion", owner.dataVersion());
            var records = pin.putArray("records");
            for (var selectedRow : selected)
                records
                    .addObject()
                    .put("tuple", selectedRow.getKey().tuple())
                    .put("recordId", selectedRow.getValue().id().toString())
                    .put("recordVersion", Long.toString(selectedRow.getValue().version()));
        }
        return result;
    }

    static NativeImportReferences recheck(
        Connection connection,
        NativeDataset target,
        JsonNode plan
    ) throws SQLException {
        return recheck(connection, target, plan, null, Map.of());
    }

    static NativeImportReferences recheck(
        Connection connection,
        NativeDataset target,
        JsonNode plan,
        Long selfHead,
        Map<UUID, Long> changedSelfVersions
    ) throws SQLException {
        JsonNode pins = plan.isEmpty()
            ? JsonNodeFactory.instance.arrayNode()
            : plan.get(0).path("references");
        var owners = new LinkedHashMap<UUID, NativeDataset>();
        var requested = new LinkedHashMap<Key, Resolution>();
        for (var pin : pins) {
            UUID id = UUID.fromString(pin.path("datasetId").asText());
            NativeDataset owner;
            try {
                owner = NativeRowTransactions.head(
                    connection,
                    target.accountId(),
                    id,
                    pin.path("schemaRevision").longValue()
                );
            } catch (VclException unavailable) {
                if (
                    List.of("DATASET_UNAVAILABLE", "SCHEMA_UNAVAILABLE").contains(
                        unavailable.code()
                    )
                ) throw new VclException("VALIDATION_STALE", "");
                throw unavailable;
            }
            long expectedHead =
                id.equals(target.schema().id()) && selfHead != null
                    ? selfHead
                    : pin.path("dataVersion").longValue();
            require(
                owner.generation().toString().equals(pin.path("generation").asText()) &&
                    owner.dataVersion() == expectedHead &&
                    owner.schema().digest().equals(pin.path("schemaDigest").asText()),
                "VALIDATION_STALE"
            );
            require(
                owners.putIfAbsent(id, owner) == null && owners.size() <= 32,
                "IMPORT_INTEGRITY"
            );
            for (var record : pin.path("records")) {
                String tuple = record.path("tuple").asText();
                var key = new Key(id, tuple, VclCanonical.sha256(tuple));
                UUID recordId = UUID.fromString(record.path("recordId").asText());
                long version = id.equals(target.schema().id())
                    ? changedSelfVersions.getOrDefault(
                          recordId,
                          record.path("recordVersion").asLong()
                      )
                    : record.path("recordVersion").asLong();
                var resolution = new Resolution(recordId, version, null);
                require(
                    requested.putIfAbsent(key, resolution) == null && requested.size() <= 2048,
                    "IMPORT_INTEGRITY"
                );
            }
        }
        var references = new NativeImportReferences(owners);
        references.load(connection, new ArrayList<>(requested.keySet()));
        for (var row : requested.entrySet())
            require(
                row.getValue().equals(references.resolved.get(row.getKey())),
                "VALIDATION_STALE"
            );
        return references;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
