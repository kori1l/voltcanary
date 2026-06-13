package com.voltcanary.telemetry.service;

import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import com.voltcanary.telemetry.config.TelemetryReconciliationProperties;
import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import com.voltcanary.telemetry.producer.TelemetryValidatedPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryValidatedReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatedReconciliationService.class);
    private static final String VALIDATED_TOPIC = "telemetry.validated.v1";

    private final JdbcTemplate jdbcTemplate;
    private final TelemetryValidatedPublisher validatedPublisher;
    private final TelemetryReconciliationProperties properties;
    private final TelemetryValidationProperties validationProperties;
    private final MeterRegistry meterRegistry;

    public TelemetryValidatedReconciliationService(
            JdbcTemplate jdbcTemplate,
            TelemetryValidatedPublisher validatedPublisher,
            TelemetryReconciliationProperties properties,
            TelemetryValidationProperties validationProperties,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.validatedPublisher = validatedPublisher;
        this.properties = properties;
        this.validationProperties = validationProperties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "#{@telemetryReconciliationProperties.interval.toMillis()}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        ReconciliationResult result = reconcileMissingValidatedEvents(Instant.now());
        if (result.enqueued() > 0 || result.skipped() > 0) {
            log.info(
                    "telemetryValidatedReconciliation enqueued={} skipped={}",
                    result.enqueued(),
                    result.skipped());
        }
    }

    @Transactional
    public ReconciliationResult reconcileMissingValidatedEvents(Instant now) {
        List<CandidateReading> candidates = findCandidates(now.minus(properties.getReadingGracePeriod()));
        int enqueued = 0;
        int skipped = 0;
        for (CandidateReading candidate : candidates) {
            Map<String, Object> metrics = metricsFor(candidate);
            if (metrics.isEmpty()) {
                skipped++;
                continue;
            }
            validatedPublisher.publish(new TelemetryValidatedEvent(
                    UUID.randomUUID(),
                    candidate.deviceId(),
                    candidate.sourceId(),
                    candidate.recordedAt(),
                    metrics,
                    qualityFlagsFor(candidate.readingId()),
                    now,
                    "v1",
                    null,
                    "telemetry-service",
                    null,
                    now), candidate.readingId());
            enqueued++;
        }
        record("enqueued", enqueued);
        record("skipped", skipped);
        return new ReconciliationResult(enqueued, skipped);
    }

    private List<CandidateReading> findCandidates(Instant receivedBefore) {
        QueryParts query = candidateQuery();
        return jdbcTemplate.query(
                query.sql(),
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(receivedBefore));
                    int index = 2;
                    for (String prefix : validationProperties.getBackfillSourcePrefixes()) {
                        ps.setString(index++, prefix + "%");
                    }
                    ps.setInt(index, properties.getBatchSize());
                },
                (rs, rowNum) -> candidate(rs));
    }

    private QueryParts candidateQuery() {
        StringBuilder sql = new StringBuilder("""
                select r.id, r.device_id, r.source_id, r.recorded_at
                from telemetry_readings r
                where r.received_at <= ?
                  and not exists (
                    select 1
                    from telemetry_event_outbox o
                    where o.reading_id = r.id
                      and o.topic = 'telemetry.validated.v1'
                  )
                  and exists (
                    select 1
                    from telemetry.measurements m
                    where m.device_id = r.device_id
                      and m.source_id = r.source_id
                      and m.recorded_at = r.recorded_at
                  )
                """);
        for (int i = 0; i < validationProperties.getBackfillSourcePrefixes().size(); i++) {
            sql.append("  and (r.source_id is null or r.source_id not like ?)\n");
        }
        sql.append("order by r.received_at asc limit ?");
        return new QueryParts(sql.toString());
    }

    private CandidateReading candidate(ResultSet rs) throws java.sql.SQLException {
        return new CandidateReading(
                rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("source_id"),
                rs.getTimestamp("recorded_at").toInstant());
    }

    private Map<String, Object> metricsFor(CandidateReading candidate) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select metric_name, metric_value
                from telemetry.measurements
                where device_id = ?
                  and source_id = ?
                  and recorded_at = ?
                order by metric_name
                """,
                ps -> {
                    ps.setObject(1, candidate.deviceId());
                    ps.setString(2, candidate.sourceId());
                    ps.setTimestamp(3, Timestamp.from(candidate.recordedAt()));
                },
                (RowCallbackHandler) rs -> metrics.put(rs.getString("metric_name"), rs.getDouble("metric_value")));
        return metrics;
    }

    private List<String> qualityFlagsFor(UUID readingId) {
        List<String> flags = jdbcTemplate.query("""
                select flag
                from telemetry_quality_flags
                where reading_id = ?
                order by flag
                """,
                (rs, rowNum) -> rs.getString("flag"),
                readingId);
        return flags.isEmpty() ? List.of("OK") : flags;
    }

    private void record(String outcome, int count) {
        if (count > 0) {
            meterRegistry.counter("telemetry.validated.reconciled", "outcome", outcome).increment(count);
        }
    }

    private record QueryParts(String sql) {
    }

    private record CandidateReading(UUID readingId, UUID deviceId, String sourceId, Instant recordedAt) {
    }

    public record ReconciliationResult(int enqueued, int skipped) {
    }
}
