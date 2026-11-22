package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.port.NativeRowQueries.AsOf;
import com.voltcanary.device.configurable.port.NativeRowQueries.AsOfProjection;
import com.voltcanary.device.configurable.port.NativeRowQueries.AsOfSegment;
import com.voltcanary.device.configurable.port.NativeRowQueries.AsOfSplit;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Exact-one event-time resolution over an authorized, immutable query result. */
final class NativeSnapshotAsOf {

    private static final VclParser PARSER = new VclParser();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private NativeSnapshotAsOf() {}

    static String projectionDigest(
        NativeDataset dataset,
        JsonNode selection,
        AsOfProjection projection
    ) {
        var intervalField = projection.intervalFieldId();
        var keys = new HashSet<>(projection.keyFieldIds());
        var selected = new HashSet<UUID>();
        selection.forEach(field -> selected.add(UUID.fromString(field.textValue())));
        if (
            keys.isEmpty() ||
            keys.size() > 8 ||
            keys.size() != projection.keyFieldIds().size() ||
            !selected.containsAll(keys) ||
            !selected.contains(intervalField) ||
            dataset.schema().type(intervalField).kind() != VclType.Kind.INTERVAL
        ) {
            throw new VclException("ASOF_FIELDS_INVALID", "");
        }
        for (UUID field : keys) {
            var type = keyType(dataset, field);
            if (type.kind() == VclType.Kind.POINT || type.kind() == VclType.Kind.INTERVAL) {
                throw new VclException("ASOF_KEY_TYPE_UNSUPPORTED", "");
            }
        }
        return VclCanonical.sha256(
            "NATIVE_ASOF_PROJECTION/1\n" +
                intervalField +
                "\n" +
                String.join("\n", keys.stream().map(UUID::toString).sorted().toList())
        );
    }

    static void index(
        Connection connection,
        UUID id,
        List<NativeRow> rows,
        AsOfProjection projection
    ) throws SQLException {
        try (
            var insert = connection.prepareStatement(
                """
                INSERT INTO cfg_native_snapshot_interval(snapshot_id,ordinal,key_digest,starts_at,ends_at)
                VALUES(?,?,?,?,?)
                """
            )
        ) {
            for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
                var values = rows.get(ordinal).values();
                var keys = new HashMap<UUID, JsonNode>();
                for (UUID field : projection.keyFieldIds())
                    keys.put(field, values.get(field).path("value"));
                if (keys.values().stream().anyMatch(JsonNode::isNull)) continue;
                var interval = values.get(projection.intervalFieldId()).path("value");
                insert.setObject(1, id);
                insert.setInt(2, ordinal);
                insert.setString(3, keyDigest(keys));
                insert.setObject(4, timestamp(interval.path("start")));
                insert.setObject(5, timestamp(interval.path("end")));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static AsOf resolve(
        Connection connection,
        NativeDataset dataset,
        Instant eventTime,
        Map<UUID, JsonNode> keys,
        List<NativeRow> rows,
        NativeQueryPage.ReadManifest manifest
    ) throws SQLException {
        requireTime(eventTime);
        try (
            var query = connection.prepareStatement(
                """
                SELECT ordinal, starts_at FROM cfg_native_snapshot_interval
                WHERE snapshot_id=? AND key_digest=? AND
                    (starts_at IS NULL OR (starts_at<=? AND (ends_at IS NULL OR ends_at>?)))
                ORDER BY CASE WHEN starts_at IS NULL THEN 0 ELSE 1 END, ordinal LIMIT 2
                """
            )
        ) {
            query.setQueryTimeout(2);
            query.setObject(1, manifest.snapshotId());
            query.setString(2, checkedKeyDigest(dataset, keys));
            query.setObject(3, eventTime.atOffset(ZoneOffset.UTC));
            query.setObject(4, eventTime.atOffset(ZoneOffset.UTC));
            try (var matches = query.executeQuery()) {
                if (!matches.next()) return new AsOf("UNKNOWN", "ASOF_MISSING", null, manifest);
                if (matches.getObject(2) == null) return new AsOf(
                    "UNKNOWN",
                    "ASOF_INTERVAL_UNKNOWN",
                    null,
                    manifest
                );
                int ordinal = matches.getInt(1);
                if (matches.next()) return new AsOf("UNKNOWN", "ASOF_AMBIGUOUS", null, manifest);
                if (ordinal < 0 || ordinal >= rows.size()) throw new VclException(
                    "SNAPSHOT_INTEGRITY",
                    ""
                );
                return new AsOf("KNOWN", null, rows.get(ordinal), manifest);
            }
        }
    }

    private record Interval(Instant start, Instant end, NativeRow row) {
        boolean contains(Instant instant) {
            return !instant.isBefore(start) && (end == null || instant.isBefore(end));
        }
    }

    static AsOfSplit split(
        Connection connection,
        NativeDataset dataset,
        Instant start,
        Instant end,
        Map<UUID, JsonNode> keys,
        List<NativeRow> rows,
        NativeQueryPage.ReadManifest manifest
    ) throws SQLException {
        requireTime(start);
        requireTime(end);
        if (!start.isBefore(end)) throw new VclException("ASOF_TIME_INVALID", "");
        var boundaries = new TreeSet<>(List.of(start, end));
        var intervals = new ArrayList<Interval>();
        boolean unknown = false;
        try (
            var query = connection.prepareStatement(
                """
                SELECT ordinal, starts_at, ends_at FROM cfg_native_snapshot_interval
                WHERE snapshot_id=? AND key_digest=? AND
                    (starts_at IS NULL OR (starts_at<? AND (ends_at IS NULL OR (ends_at>? AND ends_at>starts_at))))
                LIMIT ?
                """
            )
        ) {
            query.setQueryTimeout(2);
            query.setObject(1, manifest.snapshotId());
            query.setString(2, checkedKeyDigest(dataset, keys));
            query.setObject(3, end.atOffset(ZoneOffset.UTC));
            query.setObject(4, start.atOffset(ZoneOffset.UTC));
            query.setInt(5, NativeQuerySnapshots.MAX_ROWS + 1);
            try (var matches = query.executeQuery()) {
                int count = 0;
                while (matches.next()) {
                    int ordinal = matches.getInt(1);
                    if (
                        ++count > NativeQuerySnapshots.MAX_ROWS ||
                        ordinal < 0 ||
                        ordinal >= rows.size()
                    ) {
                        throw new VclException("SNAPSHOT_INTEGRITY", "");
                    }
                    var from = matches.getObject(2, OffsetDateTime.class);
                    if (from == null) {
                        unknown = true;
                        continue;
                    }
                    var until = matches.getObject(3, OffsetDateTime.class);
                    var interval = new Interval(
                        from.toInstant(),
                        until == null ? null : until.toInstant(),
                        rows.get(ordinal)
                    );
                    intervals.add(interval);
                    if (interval.start().isAfter(start)) boundaries.add(interval.start());
                    if (interval.end() != null && interval.end().isBefore(end)) boundaries.add(
                        interval.end()
                    );
                }
            }
        }
        var segments = new ArrayList<AsOfSegment>();
        int remainingRowBytes = 900_000;
        Instant previous = boundaries.pollFirst();
        for (Instant boundary : boundaries) {
            Instant at = previous;
            var matching = intervals
                .stream()
                .filter(interval -> interval.contains(at))
                .limit(2)
                .toList();
            String reason = unknown
                ? "ASOF_INTERVAL_UNKNOWN"
                : matching.isEmpty()
                  ? "ASOF_MISSING"
                  : matching.size() > 1
                    ? "ASOF_AMBIGUOUS"
                    : null;
            NativeRow row = reason == null ? matching.getFirst().row() : null;
            if (row != null) {
                remainingRowBytes -= VclCanonical.encode(JSON.valueToTree(row))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length;
                if (remainingRowBytes < 0) throw new VclException("SNAPSHOT_BUDGET_EXCEEDED", "");
            }
            segments.add(
                new AsOfSegment(at, boundary, reason == null ? "KNOWN" : "UNKNOWN", reason, row)
            );
            previous = boundary;
        }
        return new AsOfSplit(segments, manifest);
    }

    private static void requireTime(Instant instant) {
        if (
            instant == null ||
            instant.getNano() % 1000 != 0 ||
            instant.isBefore(MIN_TIME) ||
            instant.isAfter(MAX_TIME)
        ) {
            throw new VclException("ASOF_TIME_INVALID", "");
        }
    }

    private static String checkedKeyDigest(NativeDataset dataset, Map<UUID, JsonNode> keys) {
        var expected = new HashMap<UUID, JsonNode>();
        keys.forEach((field, raw) -> {
            var type = keyType(dataset, field);
            var literal = PARSER.literal(raw.toString());
            if (
                !type.kind().name().equals(literal.path("type").textValue()) ||
                !type.unit().equals(literal.path("unit").asText("1"))
            ) {
                throw new VclException("ASOF_KEY_TYPE_MISMATCH", "");
            }
            expected.put(field, literal.path("value"));
        });
        return keyDigest(expected);
    }

    private static VclType keyType(NativeDataset dataset, UUID field) {
        return field.equals(dataset.recordIdFieldId())
            ? new VclType(VclType.Kind.REF, false, "1", null)
            : dataset.schema().type(field);
    }

    private static java.time.OffsetDateTime timestamp(JsonNode value) {
        return value.isTextual() ? Instant.parse(value.textValue()).atOffset(ZoneOffset.UTC) : null;
    }

    private static String keyDigest(Map<UUID, JsonNode> keys) {
        var values = JsonNodeFactory.instance.objectNode();
        keys.forEach((id, value) -> values.set(id.toString(), value));
        return VclCanonical.sha256(VclCanonical.encode(values));
    }
}
