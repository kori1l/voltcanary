package com.voltcanary.analytics.variable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import com.voltcanary.analytics.producer.AnalyticsOutboxService;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.service.AlertRuleClient;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.common.events.analytics.VariableRuleEvidence;
import com.voltcanary.common.events.telemetry.MeasurementsAcceptedEvent;
import com.voltcanary.common.events.telemetry.VariableRuleSelector;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VariableRuleEvaluation {

    private final AlertRuleClient rules;
    private final VariableRuleTelemetry telemetry;
    private final MaintenanceWindowProjectionService maintenance;
    private final com.voltcanary.common.web.JdbcDeviceFence fence;
    private final JdbcTemplate jdbc;
    private final AnomalyRepository anomalies;
    private final AnalyticsOutboxService outbox;
    private final ObjectMapper mapper;

    public VariableRuleEvaluation(
        AlertRuleClient rules,
        VariableRuleTelemetry telemetry,
        MaintenanceWindowProjectionService maintenance,
        JdbcTemplate jdbc,
        AnomalyRepository anomalies,
        AnalyticsOutboxService outbox,
        ObjectMapper mapper
    ) {
        this.rules = rules;
        this.telemetry = telemetry;
        this.maintenance = maintenance;
        this.jdbc = jdbc;
        this.fence = new com.voltcanary.common.web.JdbcDeviceFence(jdbc);
        this.anomalies = anomalies;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void evaluate(MeasurementsAcceptedEvent event) {
        if (!fence.writable(event.deviceId())) return;
        if (!"LIVE".equals(event.eligibility())) return;
        if (
            event.measurements() == null ||
            event.measurements().size() > 32 ||
            event.recordedAt() == null ||
            event.accountId() == null ||
            event.deviceId() == null ||
            event.dataSourceId() == null ||
            event.eventId() == null ||
            event.sourceEventId() == null
        ) {
            throw new IllegalArgumentException("Invalid variable measurement event");
        }
        for (AlertRuleSnapshot rule : rules.effectiveRulesStrict(event.deviceId())) {
            if (
                !rule.enabled() || rule.config() == null || !rule.config().containsKey("variable")
            ) continue;
            var selector = mapper.convertValue(
                rule.config().get("variable"),
                VariableRuleSelector.class
            );
            if (
                selector == null ||
                selector.variableId() == null ||
                selector.dataSourceId() == null ||
                !selector.dataSourceId().equals(event.dataSourceId())
            ) continue;
            if (!telemetry.active(event.deviceId(), selector)) continue;
            for (var measurement : event.measurements()) {
                if (selector.variableId().equals(measurement.variableId())) evaluateOne(
                    event,
                    rule,
                    selector,
                    measurement
                );
            }
        }
    }

    private void evaluateOne(
        MeasurementsAcceptedEvent event,
        AlertRuleSnapshot rule,
        VariableRuleSelector selector,
        MeasurementsAcceptedEvent.Measurement measurement
    ) {
        if (
            jdbc.queryForObject(
                "select count(*) from variable_rule_state where rule_id=? and source_id=?",
                Integer.class,
                rule.id(),
                event.dataSourceId()
            ) == 0
        ) {
            jdbc.update(
                "insert into variable_rule_state(rule_id,source_id) values(?,?)",
                rule.id(),
                event.dataSourceId()
            );
        }
        var states = jdbc.query(
            "select last_matched_at from variable_rule_state where rule_id=? and source_id=? for update",
            (row, index) -> row.getTimestamp(1),
            rule.id(),
            event.dataSourceId()
        );
        UUID evaluation = UUID.nameUUIDFromBytes(
            (event.eventId() + ":" + rule.id() + ":" + rule.version()).getBytes(
                java.nio.charset.StandardCharsets.UTF_8
            )
        );
        if (
            jdbc.queryForObject(
                "select count(*) from variable_rule_evaluations where id=?",
                Integer.class,
                evaluation
            ) > 0
        ) return;
        String status = "NO_MATCH";
        Instant now = Instant.now();
        int cooldown = rule.cooldownSeconds() == null ? 900 : rule.cooldownSeconds();
        int dedup =
            rule.deduplicationWindowSeconds() == null ? 900 : rule.deduplicationWindowSeconds();
        if (
            selector.maxLiveAgeSeconds() < 1 ||
            selector.maxLiveAgeSeconds() > 86400 ||
            rule.threshold() == null ||
            !selector.unit().equals(measurement.unit()) ||
            !"OK".equals(measurement.quality()) ||
            !Double.isFinite(measurement.value())
        ) status = "INVALID";
        else if (
            event.recordedAt().isBefore(now.minusSeconds(selector.maxLiveAgeSeconds()))
        ) status = "STALE";
        else if (maintenance.excludesAnalyticsAt(event.deviceId(), event.recordedAt())) status =
            "MAINTENANCE";
        else if (
            !states.isEmpty() &&
            states.getFirst() != null &&
            !states
                .getFirst()
                .toInstant()
                .isBefore(event.recordedAt().minusSeconds(Math.max(cooldown, dedup)))
        ) status = "COOLDOWN";
        else if (
            VariableRulePredicate.matches(
                rule.conditionType(),
                measurement.value(),
                rule.threshold().doubleValue()
            )
        ) {
            status = "MATCHED";
            var anomaly = new Anomaly();
            anomaly.setId(evaluation);
            anomaly.setDeviceId(event.deviceId());
            anomaly.setAccountId(event.accountId());
            anomaly.setRuleId(rule.id().toString());
            anomaly.setRuleVersion(Integer.toString(rule.version()));
            anomaly.setType("VARIABLE_THRESHOLD");
            anomaly.setSeverity(rule.severity());
            anomaly.setScore(1);
            anomaly.setDescription(
                measurement.key() +
                    " = " +
                    measurement.value() +
                    " " +
                    measurement.unit() +
                    " (" +
                    rule.conditionType() +
                    " " +
                    rule.threshold() +
                    ")"
            );
            anomaly.setWindowStart(event.recordedAt());
            anomaly.setWindowEnd(event.recordedAt().plusMillis(1));
            anomaly.setInputHash(evaluation.toString());
            anomaly.setStatus("ACTIVE");
            anomaly.setDetectedAt(now);
            anomalies.save(anomaly);
            var evidence = new VariableRuleEvidence(
                measurement.variableId(),
                measurement.revision(),
                event.dataSourceId(),
                event.sourceEventId(),
                measurement.lineageId(),
                rule.version(),
                measurement.value(),
                measurement.unit(),
                selector.maxLiveAgeSeconds(),
                cooldown,
                dedup
            );
            outbox.enqueueVariableAnomaly(anomaly, evidence);
            jdbc.update(
                "update variable_rule_state set last_matched_at=? where rule_id=? and source_id=?",
                Timestamp.from(event.recordedAt()),
                rule.id(),
                event.dataSourceId()
            );
        }
        jdbc.update(
            """
            insert into variable_rule_evaluations
            (id,event_id,rule_id,rule_version,source_id,variable_id,variable_revision,lineage_id,recorded_at,status,created_at)
            values(?,?,?,?,?,?,?,?,?,?,?)
            """,
            evaluation,
            event.eventId(),
            rule.id(),
            rule.version(),
            event.dataSourceId(),
            measurement.variableId(),
            measurement.revision(),
            measurement.lineageId(),
            Timestamp.from(event.recordedAt()),
            status,
            Timestamp.from(now)
        );
    }
}
