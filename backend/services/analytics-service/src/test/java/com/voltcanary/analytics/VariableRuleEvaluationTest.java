package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import com.voltcanary.analytics.service.AlertRuleClient;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.analytics.variable.VariableRuleEvaluation;
import com.voltcanary.analytics.variable.VariableRuleTelemetry;
import com.voltcanary.common.events.telemetry.MeasurementsAcceptedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:variable-rules;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
    }
)
class VariableRuleEvaluationTest {

    @Autowired
    VariableRuleEvaluation evaluation;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AlertRuleClient rules;

    @MockitoBean
    VariableRuleTelemetry telemetry;

    @MockitoBean
    MaintenanceWindowProjectionService maintenance;

    @Test
    void exactSpikeCreatesOneTraceableAnomalyAndOutboxOnReplay() {
        var event = event(Instant.now().minusSeconds(1), "LIVE");
        var rule = rule(event);
        when(rules.effectiveRulesStrict(event.deviceId())).thenReturn(List.of(rule));
        when(telemetry.active(any(), any())).thenReturn(true);
        evaluation.evaluate(event);
        evaluation.evaluate(event);
        assertThat(status(event)).isEqualTo("MATCHED");
        assertThat(
            jdbc.queryForObject(
                "select count(*) from anomalies where device_id=?",
                Integer.class,
                event.deviceId()
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from analytics_event_outbox where topic='anomaly.detected.v1' and message_key=?",
                Integer.class,
                event.deviceId().toString()
            )
        ).isEqualTo(1);
    }

    @Test
    void staleBackfillMaintenanceAndMissingCapabilityNeverMatch() {
        var stale = event(Instant.now().minusSeconds(601), "LIVE");
        when(rules.effectiveRulesStrict(stale.deviceId())).thenReturn(List.of(rule(stale)));
        when(telemetry.active(any(), any())).thenReturn(true);
        evaluation.evaluate(stale);
        assertThat(status(stale)).isEqualTo("STALE");
        var backfill = event(Instant.now(), "BACKFILL");
        evaluation.evaluate(backfill);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from variable_rule_evaluations where event_id=?",
                Integer.class,
                backfill.eventId()
            )
        ).isZero();
        var excluded = event(Instant.now().minusSeconds(2), "LIVE");
        when(rules.effectiveRulesStrict(excluded.deviceId())).thenReturn(List.of(rule(excluded)));
        when(
            maintenance.excludesAnalyticsAt(excluded.deviceId(), excluded.recordedAt())
        ).thenReturn(true);
        evaluation.evaluate(excluded);
        assertThat(status(excluded)).isEqualTo("MAINTENANCE");
    }

    @Test
    void dependencyFailureDoesNotAcknowledgeHealthyEmptyRules() {
        var event = event(Instant.now(), "LIVE");
        when(rules.effectiveRulesStrict(event.deviceId())).thenThrow(
            new IllegalStateException("Unavailable")
        );
        assertThatThrownBy(() -> evaluation.evaluate(event)).isInstanceOf(
            IllegalStateException.class
        );
        assertThat(
            jdbc.queryForObject(
                "select count(*) from variable_rule_evaluations where event_id=?",
                Integer.class,
                event.eventId()
            )
        ).isZero();
        doReturn(List.of(rule(event)))
            .when(rules)
            .effectiveRulesStrict(event.deviceId());
        when(telemetry.active(any(), any())).thenReturn(true);
        evaluation.evaluate(event);
        assertThat(status(event)).isEqualTo("MATCHED");
    }

    private String status(MeasurementsAcceptedEvent event) {
        return jdbc.queryForObject(
            "select status from variable_rule_evaluations where event_id=?",
            String.class,
            event.eventId()
        );
    }

    private AlertRuleSnapshot rule(MeasurementsAcceptedEvent event) {
        return new AlertRuleSnapshot(
            UUID.randomUUID(),
            "THRESHOLD",
            true,
            "var_example",
            "ABOVE_THRESHOLD",
            BigDecimal.valueOf(8),
            null,
            null,
            "WARNING",
            900,
            900,
            1,
            "C",
            Map.of(
                "variable",
                Map.of(
                    "variableId",
                    event.measurements().getFirst().variableId(),
                    "dataSourceId",
                    event.dataSourceId(),
                    "unit",
                    "C",
                    "maxLiveAgeSeconds",
                    600
                )
            )
        );
    }

    private MeasurementsAcceptedEvent event(Instant at, String eligibility) {
        return new MeasurementsAcceptedEvent(
            UUID.randomUUID(),
            "v1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "source",
            at,
            Instant.now(),
            null,
            eligibility,
            List.of(
                new MeasurementsAcceptedEvent.Measurement(
                    UUID.randomUUID(),
                    1,
                    "chamber",
                    "DERIVED",
                    9,
                    "C",
                    "OK",
                    UUID.randomUUID()
                )
            ),
            Instant.now(),
            "telemetry-service"
        );
    }
}
