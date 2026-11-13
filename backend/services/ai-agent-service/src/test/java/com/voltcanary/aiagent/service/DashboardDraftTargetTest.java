package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DashboardDraftTargetTest {
    @Test void executedBaseLoadsActualDraftAndDetectsExternalChangesInsteadOfCreatingCopy() {
        var mapper = new ObjectMapper();
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var reviews = mock(PlanStore.class);
        var base = mock(PlanStore.Stored.class);
        String id = UUID.randomUUID().toString(), etag = UUID.randomUUID().toString();
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Create", "Review", true, Map.of("definition", Map.of("name", "Accepted")));
        var plan = new AgentPlan(UUID.randomUUID(), UUID.randomUUID(), "request", "review", "review", List.of(), List.of(action), Instant.now());
        when(base.plan()).thenReturn(plan);
        when(base.status()).thenReturn("SUCCESS");
        when(reviews.actionResults(plan)).thenReturn(List.of(new ActionExecutionResponse(action.id(), "CREATE_DASHBOARD", "SUCCESS", Map.of("dashboardId", id), null)));
        var target = new DashboardDraftTarget(builder, "http://dashboard", mapper, reviews);
        server.expect(requestTo("http://dashboard/dashboards/" + id + "/draft")).andExpect(header("Authorization", "Bearer current"))
                .andRespond(withSuccess("{\"dashboardId\":\"" + id + "\",\"etag\":\"" + etag + "\",\"baseRevision\":3,\"definition\":{\"name\":\"Accepted\"}}", MediaType.APPLICATION_JSON));
        assertEquals(Map.of("dashboardId", id, "draftEtag", etag, "baseRevision", 3), target.load(base, "Bearer current"));
        server.verify();
        server.reset();
        server.expect(requestTo("http://dashboard/dashboards/" + id + "/draft"))
                .andRespond(withSuccess("{\"dashboardId\":\"" + id + "\",\"etag\":\"" + UUID.randomUUID() + "\",\"baseRevision\":3,\"definition\":{\"name\":\"Changed in editor\"}}", MediaType.APPLICATION_JSON));
        var changed = assertThrows(com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected.class, () -> target.load(base, "Bearer current"));
        assertEquals("DASHBOARD_BASE_CHANGED_REVIEW_CURRENT_DRAFT", changed.getMessage());
        server.verify();
        when(base.status()).thenReturn("READY");
        assertThrows(com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected.class, () -> target.load(base, "Bearer current"));
    }
}
