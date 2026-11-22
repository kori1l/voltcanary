package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class AlertRuleClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public AlertRuleClient(
        @Qualifier("alertRestClientBuilder") RestClient.Builder restClientBuilder,
        AnalyticsDownstreamProperties downstreamProperties,
        AnalyticsInternalProperties internalProperties
    ) {
        this.restClient = restClientBuilder
            .baseUrl(downstreamProperties.getAlertService().getBaseUrl())
            .build();
        this.internalServiceToken = internalProperties.getServiceToken();
    }

    public List<AlertRuleSnapshot> effectiveRules(UUID deviceId, String correlationId) {
        try {
            AlertRuleSnapshot[] response = restClient
                .get()
                .uri("/internal/alert-rules/effective?deviceId={deviceId}", deviceId)
                .headers(headers -> {
                    headers.set("X-Correlation-Id", correlationId == null ? "" : correlationId);
                    if (!internalServiceToken.isBlank()) {
                        headers.set("X-Internal-Service-Token", internalServiceToken);
                    }
                })
                .retrieve()
                .body(AlertRuleSnapshot[].class);
            if (response == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Alert rule snapshot unavailable"
            );
            return Arrays.asList(response);
        } catch (RestClientException exception) {
            log.warn(
                "analyticsAlertRulesUnavailable deviceId={} reason={}",
                deviceId,
                exception.getClass().getSimpleName()
            );
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Alert rule service unavailable"
            );
        }
    }

    public List<AlertRuleSnapshot> effectiveRulesStrict(UUID deviceId) {
        AlertRuleSnapshot[] response = restClient
            .get()
            .uri("/internal/alert-rules/effective?deviceId={deviceId}", deviceId)
            .header("X-Internal-Service-Token", internalServiceToken)
            .retrieve()
            .body(AlertRuleSnapshot[].class);
        if (response == null) throw new IllegalStateException("Rule snapshot unavailable");
        return Arrays.asList(response);
    }
}
