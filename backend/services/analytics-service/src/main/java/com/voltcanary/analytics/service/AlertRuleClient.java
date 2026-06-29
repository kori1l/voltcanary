package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AlertRuleClient {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleClient.class);

    private final RestClient restClient;
    private final String internalServiceToken;

    public AlertRuleClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties) {
        this.restClient = restClientBuilder
                .baseUrl(downstreamProperties.getAlertService().getBaseUrl())
                .build();
        this.internalServiceToken = internalProperties.getServiceToken();
    }

    public List<AlertRuleSnapshot> effectiveRules(UUID deviceId, String correlationId) {
        try {
            AlertRuleSnapshot[] response = restClient.get()
                    .uri("/internal/alert-rules/effective?deviceId={deviceId}", deviceId)
                    .headers(headers -> {
                        headers.set("X-Correlation-Id", correlationId == null ? "" : correlationId);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .retrieve()
                    .body(AlertRuleSnapshot[].class);
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RestClientException exception) {
            log.warn("analyticsAlertRulesUnavailable deviceId={} reason={}", deviceId, exception.getClass().getSimpleName());
            return List.of();
        }
    }
}
