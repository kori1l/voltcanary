package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.dto.AlertRuleTestResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:alert-rule-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true",
                "app.alert.notifications.worker-enabled=false",
                "app.alert.reconciliation.enabled=false",
                "app.alert.authorization.enabled=false"
        })
class AlertRuleApiTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DEVICE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_DEVICE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void ruleCrudIsScopedByAccountAndCanBeTested() {
        ResponseEntity<AlertRuleResponse> createResponse = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.POST,
                entity(ACCOUNT_ID, ruleRequest("Kitchen power spike")),
                AlertRuleResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AlertRuleResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(created.ruleType()).isEqualTo("POWER_SPIKE");
        assertThat(created.conditionType()).isEqualTo("BASELINE_MULTIPLIER");
        assertThat(created.enabled()).isTrue();

        ResponseEntity<List<AlertRuleResponse>> listResponse = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.GET,
                entity(ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                });
        ResponseEntity<List<AlertRuleResponse>> otherAccountListResponse = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.GET,
                entity(OTHER_ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                });

        assertThat(listResponse.getBody()).extracting(AlertRuleResponse::id).contains(created.id());
        assertThat(otherAccountListResponse.getBody()).isEmpty();

        ResponseEntity<AlertRuleResponse> disableResponse = restTemplate.exchange(
                "/alert-rules/{id}/disable",
                HttpMethod.POST,
                entity(ACCOUNT_ID, null),
                AlertRuleResponse.class,
                created.id());
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disableResponse.getBody().enabled()).isFalse();
        assertThat(disableResponse.getBody().version()).isEqualTo(created.version() + 1);

        ResponseEntity<AlertRuleTestResponse> testResponse = restTemplate.exchange(
                "/alert-rules/test",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of("rule", ruleRequest("Preview rule"))),
                AlertRuleTestResponse.class);

        assertThat(testResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testResponse.getBody().evaluationStatus()).isEqualTo("VALID_NOT_EVALUATED");
        assertThat(testResponse.getBody().previewTitle()).isEqualTo("Power spike detected");
    }

    @Test
    void thresholdConditionRequiresThreshold() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Invalid threshold",
                        "ruleType", "THRESHOLD",
                        "scopeType", "FLEET",
                        "metric", "activePowerW",
                        "conditionType", "ABOVE_THRESHOLD",
                        "severity", "WARNING")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void effectiveRulesIncludeFleetAndMatchingDeviceRules() {
        AlertRuleResponse fleetRule = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.POST,
                entity(ACCOUNT_ID, ruleRequest("Fleet effective rule")),
                AlertRuleResponse.class).getBody();
        AlertRuleResponse deviceRule = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.POST,
                entity(ACCOUNT_ID, deviceRuleRequest("Device effective rule", DEVICE_ID)),
                AlertRuleResponse.class).getBody();
        AlertRuleResponse otherDeviceRule = restTemplate.exchange(
                "/alert-rules",
                HttpMethod.POST,
                entity(ACCOUNT_ID, deviceRuleRequest("Other device effective rule", OTHER_DEVICE_ID)),
                AlertRuleResponse.class).getBody();

        ResponseEntity<List<AlertRuleResponse>> response = restTemplate.exchange(
                "/alert-rules/effective?deviceId={deviceId}",
                HttpMethod.GET,
                entity(ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                },
                DEVICE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(AlertRuleResponse::id)
                .contains(fleetRule.id(), deviceRule.id())
                .doesNotContain(otherDeviceRule.id());
    }

    private Map<String, Object> ruleRequest(String name) {
        return Map.of(
                "name", name,
                "ruleType", "POWER_SPIKE",
                "enabled", true,
                "scopeType", "FLEET",
                "metric", "activePowerW",
                "conditionType", "BASELINE_MULTIPLIER",
                "baselineMultiplier", 1.8,
                "severity", "WARNING",
                "cooldownSeconds", 900,
                "deduplicationWindowSeconds", 900);
    }

    private Map<String, Object> deviceRuleRequest(String name, UUID deviceId) {
        return Map.ofEntries(
                Map.entry("name", name),
                Map.entry("ruleType", "POWER_SPIKE"),
                Map.entry("enabled", true),
                Map.entry("scopeType", "DEVICE"),
                Map.entry("scopeId", deviceId),
                Map.entry("metric", "activePowerW"),
                Map.entry("conditionType", "BASELINE_MULTIPLIER"),
                Map.entry("baselineMultiplier", 1.8),
                Map.entry("severity", "WARNING"),
                Map.entry("cooldownSeconds", 900),
                Map.entry("deduplicationWindowSeconds", 900));
    }

    private HttpEntity<Object> entity(UUID accountId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", USER_ID.toString());
        if (accountId != null) {
            headers.set("X-Account-Id", accountId.toString());
        }
        return new HttpEntity<>(body, headers);
    }
}
