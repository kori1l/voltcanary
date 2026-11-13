package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

final class NativeQuerySnapshots {
    static final int MAX_ROWS = 256;
    static final int MAX_BYTES = 512 * 1024;
    static final Duration TTL = Duration.ofMinutes(10);
    private static final int ACCOUNT_QUOTA = 8;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Stored(UUID generation, long dataVersion, Instant expiresAt, List<NativeRow> rows, String projectionDigest) { }

    private NativeQuerySnapshots() { }

    static NativeRowQueries.Snapshot insert(Connection connection, NativeDataset dataset, UUID actor, String scope,
            String queryDigest, List<NativeRow> rows, Instant now, String projectionDigest) throws SQLException {
        if (rows.size() > MAX_ROWS) throw new VclException("SNAPSHOT_BUDGET_EXCEEDED", "");
        String payload = VclCanonical.encode(JSON.valueToTree(rows));
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new VclException("SNAPSHOT_BUDGET_EXCEEDED", "");
        // The caller holds the account row lock, serializing quota checks, seals and account deletion.
        try (var delete = connection.prepareStatement("DELETE FROM cfg_native_query_snapshot WHERE account_id=? AND expires_at<=?")) {
            delete.setObject(1, dataset.accountId());
            delete.setObject(2, now.atOffset(ZoneOffset.UTC));
            delete.executeUpdate();
        }
        try (var count = connection.prepareStatement("SELECT COUNT(*) FROM cfg_native_query_snapshot WHERE account_id=?")) {
            count.setObject(1, dataset.accountId());
            try (var result = count.executeQuery()) {
                result.next();
                if (result.getLong(1) >= ACCOUNT_QUOTA) throw new VclException("SNAPSHOT_QUOTA", "");
            }
        }
        UUID id = UUID.randomUUID();
        Instant expiry = now.plus(TTL);
        try (var insert = connection.prepareStatement("""
                INSERT INTO cfg_native_query_snapshot(snapshot_id,account_id,actor_id,dataset_id,schema_revision,generation_id,
                    data_version,schema_digest,scope_digest,query_digest,expires_at,payload_digest,canonical_rows,asof_projection_digest)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, dataset.accountId());
            insert.setObject(3, actor);
            insert.setObject(4, dataset.schema().id());
            insert.setLong(5, dataset.schema().revision());
            insert.setObject(6, dataset.generation());
            insert.setLong(7, dataset.dataVersion());
            insert.setString(8, dataset.schema().digest());
            insert.setString(9, scope);
            insert.setString(10, queryDigest);
            insert.setObject(11, expiry.atOffset(ZoneOffset.UTC));
            insert.setString(12, VclCanonical.sha256(payload));
            insert.setString(13, payload);
            insert.setString(14, projectionDigest);
            insert.executeUpdate();
        }
        return new NativeRowQueries.Snapshot(id, expiry, rows.size());
    }

    static Stored read(Connection connection, NativeDataset dataset, UUID actor, String scope, String queryDigest,
            UUID id, Instant now) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT schema_revision,generation_id,data_version,schema_digest,scope_digest,query_digest,expires_at,payload_digest,
                    CASE WHEN OCTET_LENGTH(canonical_rows)<=? THEN canonical_rows ELSE NULL END, asof_projection_digest
                FROM cfg_native_query_snapshot WHERE snapshot_id=? AND account_id=? AND actor_id=? AND dataset_id=?
                """)) {
            query.setInt(1, MAX_BYTES);
            query.setObject(2, id);
            query.setObject(3, dataset.accountId());
            query.setObject(4, actor);
            query.setObject(5, dataset.schema().id());
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new VclException("SNAPSHOT_UNAVAILABLE", "");
                Instant expiry = result.getObject(7, OffsetDateTime.class).toInstant();
                if (!now.isBefore(expiry)) throw new VclException("SNAPSHOT_EXPIRED", "");
                if (result.getLong(1) != dataset.schema().revision() || !dataset.schema().digest().equals(result.getString(4))
                        || !scope.equals(result.getString(5)) || !queryDigest.equals(result.getString(6))) {
                    throw new VclException("SNAPSHOT_CONFLICT", "");
                }
                return new Stored(result.getObject(2, UUID.class), result.getLong(3), expiry,
                        decode(result.getString(9), result.getString(8)), result.getString(10));
            }
        }
    }

    private static List<NativeRow> decode(String payload, String digest) {
        if (payload == null || !VclCanonical.sha256(payload).equals(digest)) throw new VclException("SNAPSHOT_INTEGRITY", "");
        try {
            var rows = List.of(JSON.readValue(payload, NativeRow[].class));
            if (rows.size() > MAX_ROWS || !VclCanonical.encode(JSON.valueToTree(rows)).equals(payload)) {
                throw new VclException("SNAPSHOT_INTEGRITY", "");
            }
            return rows;
        } catch (IOException | RuntimeException invalid) {
            throw new VclException("SNAPSHOT_INTEGRITY", "");
        }
    }
}
