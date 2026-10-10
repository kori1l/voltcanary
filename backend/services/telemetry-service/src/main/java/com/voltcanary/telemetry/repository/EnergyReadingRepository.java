package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Bounded raw owner reads: no frontend detail limit, envelope, or maintenance exclusion. */
@Repository
public class EnergyReadingRepository {
    public record StoredReading(String metric,Reading reading) {
    }
    private final JdbcTemplate jdbc;

    public EnergyReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID,List<StoredReading>> read(UUID accountId,List<UUID> devices,Instant from,Instant to) {
        Map<UUID,List<StoredReading>> result = new HashMap<>();
        if (devices.isEmpty()) return result;
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        List<Object> parameters = new ArrayList<>();
        parameters.add(accountId);
        parameters.addAll(devices);
        parameters.add(Timestamp.from(from));
        parameters.add(Timestamp.from(to));
        String placeholders = String.join(",",java.util.Collections.nCopies(devices.size(),"?"));
        int[] count = {0};
        jdbc.query("""
                SELECT device_id,metric_name,metric_value,unit,source_id,quality,recorded_at
                FROM telemetry.measurements WHERE (account_id=? OR account_id IS NULL) AND device_id IN (
                """+placeholders+"""
                ) AND recorded_at>=? AND recorded_at<=? AND metric_name IN ('energyWh','activePowerW')
                ORDER BY device_id,recorded_at,metric_name,source_id LIMIT 500001
                """,row -> {
            if (++count[0]>500000) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Energy raw read exceeds500000 samples; choose a narrower period/scope");
            }
            UUID id = row.getObject("device_id",UUID.class);
            double value=row.getDouble("metric_value");
            if (row.wasNull()) value=Double.NaN;
            var reading = new Reading(row.getTimestamp("recorded_at").toInstant(),value,
                    row.getString("unit"),row.getString("source_id"),row.getString("quality"));
            result.computeIfAbsent(id,ignored -> new ArrayList<>()).add(new StoredReading(row.getString("metric_name"),reading));
        },parameters.toArray());
        return result;
    }
}
