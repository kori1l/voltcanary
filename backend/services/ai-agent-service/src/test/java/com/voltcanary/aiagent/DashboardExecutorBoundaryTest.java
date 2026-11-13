package com.voltcanary.aiagent;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.*;
import com.voltcanary.aiagent.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DashboardExecutorBoundaryTest {
    @Test void reviewedUpdateUsesSameDashboardVersionAndReceiptWithoutCreation() {
        var executor = executor();
        String id = UUID.randomUUID().toString();
        String etag = UUID.randomUUID().toString();
        var action = new AgentAction(UUID.randomUUID(), AgentTool.UPDATE_DASHBOARD_DRAFT, "Update", "Same draft", true,
                Map.of("dashboardId", id, "draftEtag", etag, "baseRevision", 2, "definition", Map.of("name", "Updated")));
        server.expect(requestTo("http://dashboards/dashboards/" + id + "/draft"))
                .andExpect(method(HttpMethod.PUT)).andExpect(header("If-Match", "\"" + etag + "\""))
                .andExpect(content().json("{\"baseRevision\":2,\"definition\":{\"name\":\"Updated\"}}", true))
                .andRespond(withServerError());
        assertThat(boundary.run(action, new HashMap<>(), () -> executor.execute(action, "Bearer current", "stable-update-key")).status()).isEqualTo("UNKNOWN");
        server.verify();
        server.reset();
        server.expect(requestTo("http://dashboards/dashboards/" + id + "/draft/receipt"))
                .andExpect(header("Idempotency-Key", DashboardCreationExecutor.domainKey("stable-update-key")))
                .andRespond(withSuccess("{\"dashboardId\":\"" + id + "\",\"definition\":{\"name\":\"Updated\"}}", MediaType.APPLICATION_JSON));
        var result = boundary.run(action, new HashMap<>(), () -> executor.reconcile(action, "Bearer current", "stable-update-key"));
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.result()).containsEntry("dashboardId", id).containsEntry("setupStatus", "PRIVATE_DRAFT_UPDATED");
        server.verify();
    }

    @Test void draftVersionConflictIsAFailureWithoutCreatingACopy() {
        var executor = executor();
        String id = UUID.randomUUID().toString();
        var action = new AgentAction(UUID.randomUUID(), AgentTool.UPDATE_DASHBOARD_DRAFT, "Update", "Conflict", true,
                Map.of("dashboardId", id, "draftEtag", UUID.randomUUID().toString(), "baseRevision", 0, "definition", Map.of("name", "Updated")));
        server.expect(requestTo("http://dashboards/dashboards/" + id + "/draft")).andExpect(method(HttpMethod.PUT)).andRespond(withStatus(HttpStatus.CONFLICT));
        assertThat(boundary.run(action, new HashMap<>(), () -> executor.execute(action, "Bearer current", "key")).status()).isEqualTo("FAILED");
        server.verify();
    }
    private final RestClient.Builder builder = com.voltcanary.common.web.JacksonRestClients.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AiAgentProperties properties = new AiAgentProperties();
    private final ActionExecutionBoundary boundary = new ActionExecutionBoundary();

    private DashboardCreationExecutor executor() {
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        return new DashboardCreationExecutor(properties, builder);
    }
    private AgentAction action() {
        return new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Private", "Review", true,
                Map.of("templateId", "my-devices", "prepareDigest", "reviewed", "prepareIntent", Map.of("version", 1), "definition", Map.of("name", "Reviewed")));
    }

    @Test void createsTheReviewedResponsiveDefinitionWithoutRebuildingOrDroppingLayouts() {
        var executor = executor();
        var layout = Map.of("desktop", List.of(Map.of("widgetId", "temperature", "x", 0, "y", 0, "w", 12, "h", 6)),
                "mobile", List.of(Map.of("widgetId", "temperature", "x", 0, "y", 0, "w", 1, "h", 6)));
        var definition = Map.of("name", "Reviewed", "layouts", layout);
        var parameters = new HashMap<String, Object>(action().parameters());
        parameters.put("definition", definition);
        parameters.put("prepareIntent", Map.of("version", 1, "panelLayout", List.of(Map.of("key", "temperature-series", "width", "FULL", "height", 6))));
        var reviewed = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Create", "Review", true, parameters);
        server.expect(requestTo("http://dashboards/dashboard-templates/my-devices/prepare"))
                .andExpect(content().json("{\"panelLayout\":[{\"key\":\"temperature-series\",\"width\":\"FULL\",\"height\":6}]}"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"reviewed\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dashboards/dashboards"))
                .andExpect(content().json(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("definition", definition)).toString(), true))
                .andRespond(withSuccess("{\"id\":\"created\",\"publishedRevision\":0}", MediaType.APPLICATION_JSON));
        assertThat(executor.execute(reviewed, "Bearer current", "reviewed-layout")).containsEntry("dashboardId", "created");
        server.verify();
    }

    @Test void lostResponseReconcilesThroughReadOnlyReceiptWithoutUsingANewTemplate() {
        var executor = executor(); var action = action(); String key = "scoped-stable-action";
        server.expect(requestTo("http://dashboards/dashboard-templates/my-devices/prepare"))
                .andExpect(header("Authorization", "Bearer current"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"reviewed\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dashboards/dashboards"))
                .andExpect(header("Idempotency-Key", DashboardCreationExecutor.domainKey(key)))
                .andExpect(content().json("{\"definition\":{\"name\":\"Reviewed\"}}", true)).andRespond(withServerError());
        assertThat(boundary.run(action, new HashMap<>(), () -> executor.execute(action, "Bearer current", key)).status()).isEqualTo("UNKNOWN");
        server.verify(); server.reset();
        server.expect(requestTo("http://dashboards/dashboards/creation-receipt"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Idempotency-Key", DashboardCreationExecutor.domainKey(key)))
                .andRespond(withSuccess("{\"id\":\"original-draft\",\"publishedRevision\":0}", MediaType.APPLICATION_JSON));
        var result = boundary.run(action, new HashMap<>(), () -> executor.reconcile(action, "Bearer current", key));
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.result()).containsEntry("dashboardId", "original-draft").containsEntry("setupStatus", "PRIVATE_DRAFT");
        server.verify();
    }

    @Test void missingReceiptDoesNotPermitCreateAfterTemplateDrift() {
        var executor = executor(); var action = action();
        server.expect(requestTo("http://dashboards/dashboards/creation-receipt")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://dashboards/dashboard-templates/my-devices/prepare"))
                .andRespond(withSuccess("{\"status\":\"READY\",\"digest\":\"changed\"}", MediaType.APPLICATION_JSON));
        assertThat(boundary.run(action, new HashMap<>(), () -> executor.reconcile(action, "Bearer current", "key")).status()).isEqualTo("FAILED");
        server.verify();
    }

    @Test void revokedReceiptAccessFailsClosedWithoutASecondCreation() {
        var executor = executor(); var action = action();
        server.expect(requestTo("http://dashboards/dashboards/creation-receipt")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(boundary.run(action, new HashMap<>(), () -> executor.reconcile(action, "Bearer current", "key")).status()).isEqualTo("FAILED");
        assertThat(DashboardCreationExecutor.domainKey("key")).hasSizeLessThan(128);
        assertThat(DashboardCreationExecutor.domainKey("key")).isNotEqualTo(DashboardCreationExecutor.domainKey("other-account-key"));
        server.verify();
    }
}
