package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.port.NativeHistoryLeases;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Pins a verified historical cut under the same account fence as generation changes. */
@RequiredArgsConstructor
public final class JdbcNativeHistoryLeases implements NativeHistoryLeases {
    private final DataSource source;
    private final Clock clock;

    @Override
    public Lease acquire(UUID account, UUID actor, UUID dataset, UUID generation, long through, int durationSeconds, String key) {
        require(through > 0 && through <= 9_007_199_254_740_991L && durationSeconds >= 60 && durationSeconds <= 86400,
                "HISTORY_REQUEST_INVALID");
        require(key != null && !key.isBlank() && key.codePointCount(0, key.length()) <= 160 && key.indexOf('\0') < 0,
                "IDEMPOTENCY_KEY_INVALID");
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            try (var query = connection.prepareStatement("SELECT * FROM cfg_native_history_lease WHERE account_id=? AND idempotency_key=?")) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        var lease = view(row, now);
                        require(actor.equals(row.getObject("actor_id", UUID.class)) && dataset.equals(lease.datasetId())
                                && generation.equals(lease.generation()) && through == lease.throughDataVersion()
                                && durationSeconds == row.getInt("duration_seconds"), "IDEMPOTENCY_KEY_REUSED");
                        return lease;
                    }
                }
            }
            var retained = NativeHistoricalDatasets.load(connection, account, dataset, generation);
            var coverage = NativeHistoryEvidence.verify(connection, retained, through);
            NativeHistoryState.verify(connection, retained, NativeHistoryState.sql(retained, null, coverage.first(), through));
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_native_history_lease WHERE account_id=? AND expires_at>? AND released_at IS NULL")) {
                query.setObject(1, account);
                query.setObject(2, now.atOffset(ZoneOffset.UTC));
                try (var row = query.executeQuery()) {
                    row.next();
                    require(row.getLong(1) < 100, "HISTORY_LEASE_QUOTA");
                }
            }
            UUID id = UUID.randomUUID();
            Instant expiry = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(durationSeconds);
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_native_history_lease(id,account_id,actor_id,dataset_id,generation,through_data_version,
                        idempotency_key,duration_seconds,expires_at) VALUES(?,?,?,?,?,?,?,?,?)
                    """, id, account, actor, dataset, generation, through, key, durationSeconds, expiry.atOffset(ZoneOffset.UTC));
            return new Lease(id, dataset, generation, through, expiry, "ACTIVE");
        });
    }

    @Override
    public Lease release(UUID account, UUID actor, UUID dataset, UUID leaseId) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            Instant now = clock.instant();
            var lease = load(connection, account, actor, dataset, leaseId, now);
            if (!lease.status().equals("ACTIVE")) return lease;
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_history_lease SET released_at=? WHERE id=?",
                    now.atOffset(ZoneOffset.UTC), leaseId);
            return load(connection, account, actor, dataset, leaseId, now);
        });
    }

    private static Lease load(Connection connection, UUID account, UUID actor, UUID dataset, UUID id, Instant now) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_native_history_lease WHERE account_id=? AND actor_id=? AND dataset_id=? AND id=?")) {
            query.setObject(1, account);
            query.setObject(2, actor);
            query.setObject(3, dataset);
            query.setObject(4, id);
            try (var row = query.executeQuery()) {
                require(row.next(), "HISTORY_LEASE_UNAVAILABLE");
                return view(row, now);
            }
        }
    }

    private static Lease view(ResultSet row, Instant now) throws SQLException {
        Instant expiry = row.getObject("expires_at", OffsetDateTime.class).toInstant();
        String status = row.getObject("released_at") != null ? "RELEASED" : now.isBefore(expiry) ? "ACTIVE" : "EXPIRED";
        return new Lease(row.getObject("id", UUID.class), row.getObject("dataset_id", UUID.class),
                row.getObject("generation", UUID.class), row.getLong("through_data_version"), expiry, status);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
