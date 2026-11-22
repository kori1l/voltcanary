package com.voltcanary.aiagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.node.TextNode;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.common.web.JacksonRestClients;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

class PrimaryAlertRuleContractTest {

    @Test
    void selectedVclSourceCannotFallBackToAnUnboundLegacyRule() {
        var selection = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        selection.addObject().put("field", "deviceId").put("ref", UUID.randomUUID().toString());
        PrimaryDomainPreparation.requireLegacyRuleSourceCompatibility(selection);
        selection
            .addObject()
            .put("field", "sourceBinding")
            .put("ref", UUID.randomUUID() + ":2");
        var failure = assertThrows(
            com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure.class,
            () -> PrimaryDomainPreparation.requireLegacyRuleSourceCompatibility(selection)
        );
        assertEquals("VCL_SOURCE_RULE_EXECUTION_UNAVAILABLE", failure.diagnostic().code());
        assertEquals(false, failure.diagnostic().retriable());
    }

    @Test
    void explanationReadsOnlyTheSelectedAlertAndItsOwningRule() {
        var builder = JacksonRestClients.builder().baseUrl("http://alerts");
        var server = MockRestServiceServer.bindTo(builder).build();
        var alert = UUID.randomUUID();
        var device = UUID.randomUUID();
        var rule = UUID.randomUUID();
        server
            .expect(requestTo("http://alerts/alerts/" + alert))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer fixture"))
            .andRespond(
                withSuccess(
                    """
                    {"deviceId":"%s","ruleId":"%s","title":"High power","severity":"WARNING","status":"OPEN",
                     "calculationWindowStart":"2026-01-01T10:00:00Z","calculationWindowEnd":"2026-01-01T11:00:00Z"}
                    """.formatted(device, rule),
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("http://alerts/alert-rules/" + rule))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer fixture"))
            .andRespond(
                withSuccess(
                    "{\"name\":\"Power threshold\",\"metric\":\"activePowerW\"}",
                    MediaType.APPLICATION_JSON
                )
            );
        var action = new AgentAction(
            UUID.randomUUID(),
            AgentTool.EXPLAIN_ALERT,
            "Explain",
            "Review",
            false,
            Map.of("alertId", alert)
        );

        var explanation = new LegacyAlertOperations(builder.build()).explainAlert(
            action,
            "Bearer fixture"
        );

        assertEquals(
            "High power is a WARNING alert in OPEN status. It was produced by Power threshold.",
            explanation.get("summary")
        );
        assertEquals(
            "/devices/" +
                device +
                "?tab=telemetry&metric=activePowerW&from=2026-01-01T10%3A00%3A00Z" +
                "&to=2026-01-01T11%3A00%3A00Z&alertId=" +
                alert,
            explanation.get("route")
        );
        server.verify();
    }

    @Test
    void reviewedRuleSendsOwnerConditionAndDeviceScope() {
        var builder = JacksonRestClients.builder().baseUrl("http://alerts");
        var server = MockRestServiceServer.bindTo(builder).build();
        String deviceId = UUID.randomUUID().toString();
        String ruleId = UUID.randomUUID().toString();
        var parameters = PrimaryDomainPreparation.alertRulePayload(
            AgentTool.CREATE_ALERT_RULE,
            Map.of(
                "deviceId",
                deviceId,
                "name",
                "High power",
                "metric",
                "activePowerW",
                "ruleType",
                "THRESHOLD",
                "conditionType",
                "ABOVE_THRESHOLD",
                "threshold",
                100
            )
        );
        var action = new AgentAction(
            UUID.randomUUID(),
            AgentTool.CREATE_ALERT_RULE,
            "Create",
            "Review",
            true,
            parameters
        );
        server
            .expect(requestTo("http://alerts/alert-rules"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer fixture"))
            .andExpect(
                content().json(
                    """
                    {"name":"High power","metric":"activePowerW","ruleType":"THRESHOLD",
                     "conditionType":"ABOVE_THRESHOLD","threshold":100,"scopeType":"DEVICE","scopeId":"%s"}
                    """.formatted(deviceId),
                    true
                )
            )
            .andRespond(withSuccess("{\"id\":\"" + ruleId + "\"}", MediaType.APPLICATION_JSON));
        assertEquals(
            ruleId,
            new LegacyAlertOperations(builder.build())
                .createAlertRule(action, "Bearer fixture")
                .get("ruleId")
        );
        server.verify();
    }

    @Test
    void updatingThresholdDoesNotReplaceExistingScope() {
        var payload = Map.<String, Object>of(
            "ruleId",
            UUID.randomUUID().toString(),
            "threshold",
            100
        );
        assertEquals(
            payload,
            PrimaryDomainPreparation.alertRulePayload(AgentTool.UPDATE_ALERT_RULE, payload)
        );
        var builder = JacksonRestClients.builder().baseUrl("http://alerts");
        var server = MockRestServiceServer.bindTo(builder).build();
        String ruleId = (String) payload.get("ruleId");
        server
            .expect(requestTo("http://alerts/alert-rules/" + ruleId))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer fixture"))
            .andExpect(content().json("{\"threshold\":100}", true))
            .andRespond(withSuccess("{\"id\":\"" + ruleId + "\"}", MediaType.APPLICATION_JSON));
        var action = new AgentAction(
            UUID.randomUUID(),
            AgentTool.UPDATE_ALERT_RULE,
            "Update",
            "Review",
            true,
            payload
        );
        assertEquals(
            ruleId,
            new LegacyAlertOperations(builder.build())
                .updateAlertRule(action, "Bearer fixture")
                .get("ruleId")
        );
        server.verify();
    }

    @Test
    void rejectsChangingTheRuleSelectedByThePlan() {
        var selected = TextNode.valueOf(UUID.randomUUID().toString());
        PrimaryDomainPreparation.requireRuleBinding(selected, selected);
        var error = assertThrows(IntentPlans.Rejected.class, () ->
            PrimaryDomainPreparation.requireRuleBinding(
                selected,
                TextNode.valueOf(UUID.randomUUID().toString())
            )
        );
        assertEquals("RULE_BINDING_CHANGED", error.getMessage());
    }
}
