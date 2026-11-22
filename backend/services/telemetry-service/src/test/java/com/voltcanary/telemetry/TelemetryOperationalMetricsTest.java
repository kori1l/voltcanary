package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voltcanary.telemetry.observability.TelemetryOperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class TelemetryOperationalMetricsTest {

    @Test
    void databaseOutageIsNotReportedAsAnEmptyQueue() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenThrow(
            new DataAccessResourceFailureException("Database unavailable")
        );
        when(jdbc.queryForObject(anyString(), eq(java.sql.Timestamp.class))).thenThrow(
            new DataAccessResourceFailureException("Database unavailable")
        );
        var registry = new SimpleMeterRegistry();
        new TelemetryOperationalMetrics(jdbc, registry);
        assertThat(
            registry.get("telemetry.outbox.depth").tag("status", "PENDING").gauge().value()
        ).isNaN();
        assertThat(
            registry.get("telemetry.outbox.oldest.pending.age.seconds").gauge().value()
        ).isNaN();
    }
}
