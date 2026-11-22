package com.voltcanary.analytics.variable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.dto.RuleEvaluationRequest;
import com.voltcanary.analytics.dto.RuleEvaluationResponse;
import com.voltcanary.common.events.telemetry.VariableRuleSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VariableRuleDryRun {

    private final VariableRuleTelemetry telemetry;
    private final ObjectMapper mapper;

    public RuleEvaluationResponse evaluate(RuleEvaluationRequest request) {
        var selector = mapper.convertValue(
            request.config().get("variable"),
            VariableRuleSelector.class
        );
        if (
            selector == null ||
            selector.variableId() == null ||
            selector.dataSourceId() == null ||
            selector.unit() == null ||
            !selector
                .unit()
                .equals(request.thresholdUnit() == null ? "" : request.thresholdUnit()) ||
            request.threshold() == null ||
            !Double.isFinite(request.threshold())
        ) {
            throw new IllegalArgumentException("Invalid variable threshold test");
        }
        Instant to = request.to() == null ? Instant.now() : request.to();
        Instant from = request.from() == null ? to.minus(Duration.ofHours(24)) : request.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("Invalid variable test period");
        }
        var samples = telemetry.samples(request.deviceId(), selector, from, to);
        if (samples.isEmpty()) {
            return new RuleEvaluationResponse(
                false,
                0,
                false,
                request.severity(),
                "Variable threshold",
                "No eligible measurements in the selected period.",
                "INSUFFICIENT_DATA",
                List.of()
            );
        }
        var matched = samples
            .stream()
            .filter(sample ->
                VariableRulePredicate.matches(
                    request.conditionType(),
                    sample.value(),
                    request.threshold()
                )
            )
            .toList();
        var evidence = matched
            .stream()
            .limit(20)
            .map(sample ->
                new RuleEvaluationResponse.Evidence(
                    sample.timestamp(),
                    request.metric(),
                    sample.value(),
                    request.threshold(),
                    null
                )
            )
            .toList();
        return new RuleEvaluationResponse(
            !matched.isEmpty(),
            matched.size(),
            !matched.isEmpty(),
            request.severity(),
            "Variable threshold",
            "Evaluated full-resolution stored observations; dry run sends no notifications.",
            "EVALUATED",
            evidence
        );
    }
}
