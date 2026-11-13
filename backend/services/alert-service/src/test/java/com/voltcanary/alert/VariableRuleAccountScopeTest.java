package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.service.AlertAccessService;
import com.voltcanary.alert.service.VariableRuleValidation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.kafka.consume-enabled=false", "app.alert.notifications.worker-enabled=false",
        "app.alert.reconciliation.enabled=false", "app.alert.authorization.enabled=false"})
class VariableRuleAccountScopeTest extends SignedUserTest {
    @Autowired TestRestTemplate http;
    @MockitoBean AlertAccessService access;
    @MockitoBean VariableRuleValidation variableValidation;

    @Test
    void gatewayUserContextResolvesActualAccountWithoutCallerSuppliedAccountHeader() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID(), variable = UUID.randomUUID();
        when(access.ownAccount(user.toString())).thenReturn(account);
        when(variableValidation.applies(anyString(), any())).thenAnswer(call -> call.getArgument(0, String.class).startsWith("var_"));
        when(variableValidation.config(any())).thenReturn(Map.of());
        var headers = new HttpHeaders();
        headers.setBearerAuth(com.voltcanary.common.test.SignedUserTokens.create(user, "actor@example.invalid", "USER"));
        var body = Map.of("name", "Scoped variable threshold", "ruleType", "THRESHOLD", "enabled", true,
                "scopeType", "DEVICE", "scopeId", UUID.randomUUID(), "metric", "var_" + variable.toString().replace("-", ""),
                "conditionType", "ABOVE_THRESHOLD", "threshold", 24, "thresholdUnit", "C", "severity", "WARNING");
        var created = http.exchange("/alert-rules", HttpMethod.POST, new HttpEntity<>(body, headers), AlertRuleResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().accountId()).isEqualTo(account).isNotEqualTo(user);
        var listed = http.exchange("/alert-rules", HttpMethod.GET, new HttpEntity<>(headers), AlertRuleResponse[].class);
        assertThat(listed.getBody()).extracting(AlertRuleResponse::id).contains(created.getBody().id());
        var loaded = http.exchange("/alert-rules/" + created.getBody().id(), HttpMethod.GET, new HttpEntity<>(headers), AlertRuleResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
