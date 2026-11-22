package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.repository.AlertRuleRepository;
import com.voltcanary.alert.service.VariableAnomalyAdmission;
import com.voltcanary.alert.service.VariableRuleValidation;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.VariableRuleEvidence;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class VariableAnomalyAdmissionTest {

    @Test
    void delayedRevokedAndEditedRulesCannotCreateAlertsAndOutagesRemainRetryable() {
        var rules = mock(AlertRuleRepository.class);
        var validation = mock(VariableRuleValidation.class);
        var admission = new VariableAnomalyAdmission(rules, validation, new ObjectMapper());
        UUID ruleId = UUID.randomUUID(),
            device = UUID.randomUUID(),
            account = UUID.randomUUID();
        UUID variable = UUID.randomUUID(),
            source = UUID.randomUUID();
        var rule = new AlertRule();
        rule.setId(ruleId);
        rule.setAccountId(account);
        rule.setScopeId(device);
        rule.setEnabled(true);
        rule.setVersion(1);
        rule.setCreatedBy(UUID.randomUUID());
        when(rules.findById(ruleId)).thenReturn(Optional.of(rule));
        when(validation.config(rule)).thenReturn(
            Map.of(
                "variable",
                Map.of(
                    "variableId",
                    variable.toString(),
                    "dataSourceId",
                    source.toString(),
                    "unit",
                    "C",
                    "maxLiveAgeSeconds",
                    600
                )
            )
        );
        var evidence = new VariableRuleEvidence(
            variable,
            1,
            source,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            24.7,
            "C",
            600,
            0,
            0
        );
        Instant now = Instant.now();
        var event = event(ruleId, device, account, now, evidence);
        assertThat(admission.accepts(event)).isTrue();
        rule.setEnabled(false);
        assertThat(admission.accepts(event)).isFalse();
        rule.setEnabled(true);
        rule.setVersion(2);
        assertThat(admission.accepts(event)).isFalse();
        rule.setVersion(1);
        assertThat(
            admission.accepts(event(ruleId, device, account, now.minusSeconds(601), evidence))
        ).isFalse();
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST))
            .when(validation)
            .validate(any(), any(), isNull());
        assertThat(admission.accepts(event)).isFalse();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(validation)
            .validate(any(), any(), isNull());
        assertThatThrownBy(() -> admission.accepts(event)).isInstanceOf(
            ResponseStatusException.class
        );
    }

    private AnomalyDetectedEvent event(
        UUID rule,
        UUID device,
        UUID account,
        Instant time,
        VariableRuleEvidence evidence
    ) {
        return new AnomalyDetectedEvent(
            UUID.randomUUID(),
            device,
            UUID.randomUUID(),
            rule.toString(),
            time,
            time.plusMillis(1),
            "hash",
            "VARIABLE_THRESHOLD",
            "WARNING",
            1,
            "Threshold",
            Instant.now(),
            "v1",
            null,
            "analytics-service",
            account,
            Instant.now(),
            evidence
        );
    }
}
