package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** One bounded SQL read of canonical streams; unqualified samples remain barriers. */
@Repository
public class EnergyReadingRepository {
    public record StoredReading(String metric, Reading reading) { }
    public record Range(UUID deviceId, String sourceId, String metric, Instant from, Instant to, boolean inclusiveEnd) { }
    public record ReadQuery(String sql, List<Object> parameters) { }
    private record Stream(UUID deviceId, String sourceId, String metric) { }
    private final JdbcTemplate jdbc;

    public EnergyReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, List<StoredReading>> readSelected(UUID accountId, List<Range> ranges) {
        Map<UUID, List<StoredReading>> result = new HashMap<>();
        if (ranges.isEmpty()) return result;
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        ReadQuery query = selectedQuery(accountId, ranges);
        int[] count = {0};
        jdbc.query(query.sql(), row -> {
            if (++count[0] > 500000) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Energy selected read exceeds500000 samples; narrow the intervals");
            }
            double value = row.getDouble("metric_value");
            if (row.wasNull()) value = Double.NaN;
            var reading = new Reading(row.getTimestamp("recorded_at").toInstant(), value,
                    row.getString("unit"), row.getString("source_id"), row.getString("quality"));
            result.computeIfAbsent(row.getObject("device_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new StoredReading(row.getString("metric_name"), reading));
        }, query.parameters().toArray());
        return result;
    }

    public static ReadQuery selectedQuery(UUID accountId, List<Range> ranges) {
        List<Range> merged = merge(ranges);
        if (merged.size() > 10000) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Energy selection exceeds10000 canonical ranges; narrow the scope");
        }
        List<Object> parameters = new ArrayList<>();
        for (Range range : merged) {
            parameters.add(range.deviceId());
            parameters.add(range.sourceId());
            parameters.add(range.metric());
            parameters.add(Timestamp.from(range.from()));
            parameters.add(Timestamp.from(range.to()));
            parameters.add(range.inclusiveEnd());
        }
        parameters.add(accountId);
        String values = String.join(",", java.util.Collections.nCopies(merged.size(),
                "(?::uuid,?::text,?::text,?::timestamp,?::timestamp,?::boolean)"));
        String sql = """
                SELECT m.* FROM (VALUES %s) ranges(device_id,source_id,metric_name,start_at,end_at,inclusive_end)
                JOIN LATERAL (
                  SELECT device_id,metric_name,metric_value,unit,source_id,quality,recorded_at
                  FROM telemetry.measurements m
                  WHERE m.device_id=ranges.device_id AND m.source_id=ranges.source_id
                  AND m.metric_name=ranges.metric_name AND m.recorded_at>=ranges.start_at
                  AND m.metric_name IN ('energyWh','activePowerW')
                  AND m.recorded_at<=ranges.end_at AND (ranges.inclusive_end OR m.recorded_at<ranges.end_at)
                  AND (m.account_id=? OR m.account_id IS NULL)
                  OFFSET 0
                ) m ON true
                ORDER BY m.device_id,m.source_id,m.metric_name,m.recorded_at LIMIT 500001
                """.formatted(values);
        return new ReadQuery(sql, List.copyOf(parameters));
    }

    /** Disjoint ranges prevent overlapping intervals duplicating real measurement rows. */
    public static List<Range> merge(List<Range> ranges) {
        Map<Stream, List<Range>> streams = new LinkedHashMap<>();
        for (Range range : ranges) {
            streams.computeIfAbsent(new Stream(range.deviceId(), range.sourceId(), range.metric()),
                    ignored -> new ArrayList<>()).add(range);
        }
        List<Range> result = new ArrayList<>();
        for (List<Range> stream : streams.values()) {
            stream.sort(Comparator.comparing(Range::from).thenComparing(Range::to));
            Range current = stream.getFirst();
            for (int index = 1; index < stream.size(); index++) {
                Range next = stream.get(index);
                if (next.from().isAfter(current.to())) {
                    result.add(current);
                    current = next;
                } else {
                    boolean later = next.to().isAfter(current.to());
                    Instant to = later ? next.to() : current.to();
                    boolean inclusive = later ? next.inclusiveEnd() : current.inclusiveEnd()
                            || (next.to().equals(current.to()) && next.inclusiveEnd());
                    current = new Range(current.deviceId(), current.sourceId(), current.metric(), current.from(), to, inclusive);
                }
            }
            result.add(current);
        }
        return List.copyOf(result);
    }
}
