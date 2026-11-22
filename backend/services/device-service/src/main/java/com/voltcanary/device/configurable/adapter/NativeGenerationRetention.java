package com.voltcanary.device.configurable.adapter;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Reclaims retired PostgreSQL tables atomically with the durable generation marker. */
@Slf4j
public final class NativeGenerationRetention {

    private record Candidate(UUID account, UUID actor, UUID dataset, UUID generation) {}

    private final DataSource source;
    private final Clock clock;
    private final Duration grace;

    public NativeGenerationRetention(DataSource source, Clock clock, Duration grace) {
        if (grace.compareTo(Duration.ofDays(1)) < 0) throw new IllegalArgumentException(
            "Native retention grace must be at least one day"
        );
        this.source = source;
        this.clock = clock;
        this.grace = grace;
    }

    @Scheduled(fixedDelayString = "${app.native-data.retention-delay-ms:60000}")
    public void reclaimExpired() {
        List<Candidate> candidates;
        try {
            candidates = candidates();
        } catch (SQLException unavailable) {
            log.warn("Native generation retention scan failed");
            return;
        }
        for (var candidate : candidates) {
            try {
                reclaim(candidate);
            } catch (com.voltcanary.vcl.VclException unavailable) {
                log.warn(
                    "Native generation retention deferred for {} ({})",
                    candidate.generation(),
                    unavailable.code()
                );
            }
        }
    }

    private List<Candidate> candidates() throws SQLException {
        try (var connection = source.getConnection()) {
            if (
                !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
            ) return List.of();
            try (
                var query = connection.prepareStatement(
                    """
                    SELECT g.account_id,a.user_id,g.dataset_id,g.generation
                    FROM cfg_native_generation g JOIN accounts a ON a.id=g.account_id AND a.status='ACTIVE'
                    JOIN cfg_dataset d ON d.id=g.dataset_id AND d.account_id=g.account_id AND d.status='READY'
                    WHERE g.status='RETIRED' AND g.reclaimed_at IS NULL AND g.retired_at<=?
                        AND g.generation<>d.generation AND g.physical_id<>d.physical_id
                        AND NOT EXISTS (SELECT 1 FROM cfg_native_query_snapshot s WHERE s.account_id=g.account_id
                            AND s.dataset_id=g.dataset_id AND s.generation_id=g.generation AND s.expires_at>?)
                        AND NOT EXISTS (SELECT 1 FROM cfg_native_history_lease l WHERE l.account_id=g.account_id
                            AND l.dataset_id=g.dataset_id AND l.generation=g.generation AND l.expires_at>? AND l.released_at IS NULL)
                    ORDER BY g.retired_at,g.generation LIMIT 10
                    """
                )
            ) {
                var now = clock.instant();
                query.setObject(1, now.minus(grace).atOffset(ZoneOffset.UTC));
                query.setObject(2, now.atOffset(ZoneOffset.UTC));
                query.setObject(3, now.atOffset(ZoneOffset.UTC));
                query.setQueryTimeout(2);
                var result = new ArrayList<Candidate>();
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        result.add(
                            new Candidate(
                                rows.getObject(1, UUID.class),
                                rows.getObject(2, UUID.class),
                                rows.getObject(3, UUID.class),
                                rows.getObject(4, UUID.class)
                            )
                        );
                }
                return result;
            }
        }
    }

    private void reclaim(Candidate candidate) {
        NativeRowUnitOfWork.run(
            source,
            candidate.account(),
            candidate.actor(),
            (connection, scope) -> {
                var now = clock.instant();
                UUID physical;
                try (
                    var query = connection.prepareStatement(
                        """
                        SELECT g.physical_id FROM cfg_native_generation g
                        JOIN cfg_dataset d ON d.id=g.dataset_id AND d.account_id=g.account_id AND d.status='READY'
                        WHERE g.account_id=? AND g.dataset_id=? AND g.generation=? AND g.status='RETIRED'
                            AND g.reclaimed_at IS NULL AND g.retired_at<=? AND g.generation<>d.generation AND g.physical_id<>d.physical_id
                            AND NOT EXISTS (SELECT 1 FROM cfg_native_query_snapshot s WHERE s.account_id=g.account_id
                                AND s.dataset_id=g.dataset_id AND s.generation_id=g.generation AND s.expires_at>?)
                            AND NOT EXISTS (SELECT 1 FROM cfg_native_history_lease l WHERE l.account_id=g.account_id
                                AND l.dataset_id=g.dataset_id AND l.generation=g.generation AND l.expires_at>? AND l.released_at IS NULL)
                        """
                    )
                ) {
                    query.setObject(1, candidate.account());
                    query.setObject(2, candidate.dataset());
                    query.setObject(3, candidate.generation());
                    query.setObject(4, now.minus(grace).atOffset(ZoneOffset.UTC));
                    query.setObject(5, now.atOffset(ZoneOffset.UTC));
                    query.setObject(6, now.atOffset(ZoneOffset.UTC));
                    try (var row = query.executeQuery()) {
                        if (!row.next()) return null;
                        physical = row.getObject(1, UUID.class);
                    }
                }
                String token = physical.toString().replace("-", "");
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "DROP TABLE IF EXISTS " + NativeSqlLayout.identifier("cfg_hist_" + token)
                );
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "DROP TABLE IF EXISTS " + NativeSqlLayout.identifier("cfg_data_" + token)
                );
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "DELETE FROM cfg_native_history_checkpoint WHERE dataset_id=? AND generation=?",
                    candidate.dataset(),
                    candidate.generation()
                );
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "DELETE FROM cfg_native_history_commit WHERE dataset_id=? AND generation=?",
                    candidate.dataset(),
                    candidate.generation()
                );
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "UPDATE cfg_native_generation SET reclaimed_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND generation=?",
                    candidate.dataset(),
                    candidate.generation()
                );
                return null;
            }
        );
    }
}
