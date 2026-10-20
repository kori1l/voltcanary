package com.voltcanary.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.repository.AlertRuleRepository;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.telemetry.VariableRuleSelector;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Recheck revocation and age when a queued anomaly finally reaches alerts. */
@Component
public class VariableAnomalyAdmission {
    private final AlertRuleRepository rules;
    private final VariableRuleValidation validation;
    private final ObjectMapper mapper;

    public VariableAnomalyAdmission(AlertRuleRepository rules, VariableRuleValidation validation, ObjectMapper mapper) {
        this.rules = rules;
        this.validation = validation;
        this.mapper = mapper;
    }

    public boolean accepts(AnomalyDetectedEvent event) {
        var evidence = event.variableEvidence();
        if (evidence == null) return true;
        if (event.ruleId() == null || event.windowStart() == null || evidence.maxLiveAgeSeconds() < 1
                || event.windowStart().isBefore(Instant.now().minusSeconds(evidence.maxLiveAgeSeconds()))) return false;
        UUID ruleId;
        try { ruleId = UUID.fromString(event.ruleId()); }
        catch (IllegalArgumentException invalid) { return false; }
        var rule = rules.findById(ruleId).orElse(null);
        if (rule == null || !rule.isEnabled() || rule.getVersion() != evidence.ruleVersion()
                || !rule.getAccountId().equals(event.accountId()) || !event.deviceId().equals(rule.getScopeId())
                || rule.getCreatedBy() == null) return false;
        var selector = mapper.convertValue(validation.config(rule).get("variable"), VariableRuleSelector.class);
        if (selector == null || !evidence.variableId().equals(selector.variableId())
                || !evidence.dataSourceId().equals(selector.dataSourceId()) || !evidence.unit().equals(selector.unit())) return false;
        try {
            validation.validate(rule, rule.getCreatedBy().toString(), null);
            return true;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) throw failure;
            return false;
        }
    }
}
