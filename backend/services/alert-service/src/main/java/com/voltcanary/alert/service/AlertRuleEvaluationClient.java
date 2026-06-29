package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertDownstreamProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import com.voltcanary.alert.dto.AlertRuleTestResponse;
import com.voltcanary.alert.dto.CreateAlertRuleRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AlertRuleEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleEvaluationClient.class);

    private final RestClient restClient;
    private final String internalServiceToken;
    private final String internalServiceName;

    public AlertRuleEvaluationClient(
            RestClient.Builder restClientBuilder,
            AlertDownstreamProperties downstreamProperties,
            AlertInternalProperties internalProperties) {
        this.restClient = restClientBuilder
                .baseUrl(downstreamProperties.getAnalyticsService().getBaseUrl())
                .build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
    }

    public AlertRuleTestResponse test(
            UUID deviceId,
            CreateAlertRuleRequest rule,
            Instant from,
            Instant to,
            String userId,
            String correlationId) {
        try {
            AnalyticsRuleTestResponse response = restClient.post()
                    .uri("/analytics/rules/test")
                    .headers(headers -> {
                        if (userId != null && !userId.isBlank()) {
                            headers.set("X-User-Id", userId);
                        }
                        headers.set("X-Source-Service", internalServiceName);
                        headers.set("X-Correlation-Id", correlationId == null ? "" : correlationId);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .body(new AnalyticsRuleTestRequest(
                            deviceId,
                            rule.ruleType(),
                            rule.metric(),
                            rule.conditionType(),
                            rule.threshold() == null ? null : rule.threshold().doubleValue(),
                            rule.thresholdUnit(),
                            rule.baselineMultiplier() == null ? null : rule.baselineMultiplier().doubleValue(),
                            rule.minPoints(),
                            rule.severity(),
                            from,
                            to))
                    .retrieve()
                    .body(AnalyticsRuleTestResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analytics rule evaluation unavailable");
            }
            return toAlertResponse(response);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("alertRuleEvaluationFailed deviceId={} reason={}", deviceId, exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analytics rule evaluation unavailable", exception);
        }
    }

    private AlertRuleTestResponse toAlertResponse(AnalyticsRuleTestResponse response) {
        return new AlertRuleTestResponse(
                response.matched(),
                response.matchCount(),
                response.wouldCreateAlert(),
                response.severity(),
                response.previewTitle(),
                response.previewDescription(),
                response.evaluationStatus(),
                response.evidence() == null ? List.of() : response.evidence().stream()
                        .map(item -> new AlertRuleTestResponse.AlertRuleTestEvidence(
                                item.timestamp(),
                                item.metric(),
                                item.value(),
                                item.threshold(),
                                parseUuid(item.alertId())))
                        .toList());
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record AnalyticsRuleTestRequest(
            UUID deviceId,
            String ruleType,
            String metric,
            String conditionType,
            Double threshold,
            String thresholdUnit,
            Double baselineMultiplier,
            Integer minPoints,
            String severity,
            Instant from,
            Instant to) {
    }

    private record AnalyticsRuleTestResponse(
            boolean matched,
            int matchCount,
            boolean wouldCreateAlert,
            String severity,
            String previewTitle,
            String previewDescription,
            String evaluationStatus,
            List<Evidence> evidence) {
    }

    private record Evidence(
            Instant timestamp,
            String metric,
            Double value,
            Double threshold,
            String alertId) {
    }
}
