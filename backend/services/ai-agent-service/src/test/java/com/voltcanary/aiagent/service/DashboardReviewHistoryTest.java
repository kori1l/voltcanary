package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.common.web.JacksonRestClients;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

class DashboardReviewHistoryTest {

    @Test
    void restoresOnlyAnExecutedAncestorOfTheSameDashboard() {
        var store = mock(PlanStore.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var drafts = mock(DashboardDraftTarget.class);
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        String dashboardId = UUID.randomUUID().toString();
        var original = draftReview(scope, "Original", "SUCCESS");
        var foreign = draftReview(scope, "Another dashboard", "SUCCESS");
        var pending = draftReview(scope, "Pending version", "SUPERSEDED");
        var parent = draftReview(scope, "Current", "SUCCESS");
        when(drafts.savedId(original)).thenReturn(dashboardId);
        when(drafts.savedId(foreign)).thenReturn(UUID.randomUUID().toString());
        var chain = List.of(parent, pending, foreign, original);
        for (int i = 0; i < chain.size() - 1; i++) {
            var newer = chain.get(i).plan().id();
            var older = chain.get(i + 1);
            when(store.context(newer, scope)).thenReturn(
                Map.of("v2ParentReviewId", older.plan().id().toString())
            );
            when(store.find(older.plan().id(), scope)).thenReturn(older);
        }
        var request = new AgentPlan(
            UUID.randomUUID(),
            scope.userId(),
            "Undo",
            "Undo",
            "Undo",
            List.of(),
            List.of(
                new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.UPDATE_DASHBOARD_DRAFT,
                    "Undo",
                    "Undo",
                    true,
                    Map.of("restoreSteps", 1)
                )
            ),
            Instant.now()
        );

        var restored = new DashboardReviewHistory(store, contexts).restoreDraft(
            request,
            parent,
            "Bearer current",
            drafts,
            dashboardId
        );

        assertThat(restored.plan().actions().getFirst().parameters().get("definition")).isEqualTo(
            original.plan().actions().getFirst().parameters().get("definition")
        );
        assertThat(
            restored.context().context().get(DashboardReviewHistory.RESTORED_FROM)
        ).isEqualTo(original.plan().id().toString());
        verify(contexts).revalidate(original.refs(), "Bearer current");

        var builder = JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiAgentProperties();
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        var executor = new DashboardCreationExecutor(properties, builder);
        var restoredAction = restored.plan().actions().getFirst();
        var parameters = new LinkedHashMap<>(restoredAction.parameters());
        parameters.putAll(
            Map.of("dashboardId", dashboardId, "draftEtag", "current-etag", "baseRevision", 7)
        );
        var action = new AgentAction(
            restoredAction.id(),
            AgentTool.UPDATE_DASHBOARD_DRAFT,
            "Undo",
            "Undo",
            true,
            parameters
        );
        server
            .expect(requestTo("http://dashboards/dashboards/" + dashboardId + "/draft"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("If-Match", "\"current-etag\""))
            .andExpect(
                content().json("{\"baseRevision\":7,\"definition\":{\"name\":\"Original\"}}", true)
            )
            .andRespond(
                withSuccess(
                    "{\"dashboardId\":\"" +
                        dashboardId +
                        "\",\"definition\":{\"name\":\"Original\"}}",
                    MediaType.APPLICATION_JSON
                )
            );

        assertThat(executor.execute(action, "Bearer current", "restore-key")).containsEntry(
            "dashboardId",
            dashboardId
        );
        server.verify();
    }

    private PlanStore.Stored draftReview(
        AuthorizedPlanningContext.Scope scope,
        String name,
        String status
    ) {
        var action = new AgentAction(
            UUID.randomUUID(),
            AgentTool.UPDATE_DASHBOARD_DRAFT,
            name,
            name,
            true,
            Map.of("definition", Map.of("name", name))
        );
        var plan = new AgentPlan(
            UUID.randomUUID(),
            scope.userId(),
            name,
            name,
            name,
            List.of(),
            List.of(action),
            Instant.now()
        );
        return new PlanStore.Stored(
            plan,
            scope.accountId(),
            1,
            "digest",
            Instant.now().plusSeconds(600),
            status,
            null,
            List.of()
        );
    }

    @Test
    void restoresExactFrozenDefinitionFromOwnerScopedAncestryAndRevalidatesRefs() {
        var store = mock(PlanStore.class);
        var contexts = mock(AuthorizedPlanningContext.class);
        var user = UUID.randomUUID();
        var account = UUID.randomUUID();
        var scope = new AuthorizedPlanningContext.Scope(user, account);
        var parameters = Map.<String, Object>of(
            "definition",
            Map.of("name", "Original", "widgets", List.of()),
            "prepareDigest",
            "frozen",
            "prepareIntent",
            Map.of("name", "Original"),
            "planningProtocol",
            DashboardOperationProtocol.VERSION,
            "operationChanges",
            Map.of("widget", List.of("created")),
            "expectedOutcomes",
            List.of(Map.of("target", "widget")),
            "responsiveReflow",
            true
        );
        var original = new AgentPlan(
            UUID.randomUUID(),
            user,
            "Dashboard",
            "Review",
            "Review",
            List.of(),
            List.of(
                new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DASHBOARD,
                    "Original",
                    "Review",
                    true,
                    parameters
                )
            ),
            Instant.now()
        );
        var old = new PlanStore.Stored(
            original,
            account,
            1,
            "first",
            Instant.now().plusSeconds(600),
            "READY",
            null,
            List.of()
        );
        var parentPlan = new AgentPlan(
            UUID.randomUUID(),
            user,
            "Dashboard followup",
            "Review",
            "Review",
            List.of(),
            original.actions(),
            Instant.now()
        );
        var parent = new PlanStore.Stored(
            parentPlan,
            account,
            1,
            "second",
            Instant.now().plusSeconds(600),
            "READY",
            null,
            List.of(
                new AuthorizedPlanningContext.ResourceRef(
                    "current-source",
                    "source",
                    "source-id",
                    Map.of()
                )
            )
        );
        when(store.context(parentPlan.id(), scope)).thenReturn(
            Map.of("_clarification", Map.of("parentPlanId", original.id().toString()))
        );
        when(store.find(original.id(), scope)).thenReturn(old);
        when(store.context(original.id(), scope)).thenReturn(Map.of());
        var requested = new AgentPlan(
            UUID.randomUUID(),
            user,
            "Верни назад",
            "Restore",
            "Restore",
            List.of(),
            List.of(
                new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DASHBOARD,
                    "Restore",
                    "Restore",
                    true,
                    Map.of("restoreSteps", 1)
                )
            ),
            Instant.now()
        );
        var history = new DashboardReviewHistory(store, contexts);
        assertThat(history.previousIds(parent)).containsExactly(original.id());
        var restored = history.restore(requested, parent, "Bearer current");
        assertThat(restored.plan().actions().getFirst().parameters()).isEqualTo(parameters);
        assertThat(restored.plan().id()).isEqualTo(requested.id());
        assertThat(restored.plan().actions().getFirst().id()).isNotEqualTo(
            original.actions().getFirst().id()
        );
        verify(contexts).revalidate(old.refs(), "Bearer current");
        var keep = new AgentPlan(
            UUID.randomUUID(),
            user,
            "Ничего не меняй",
            "Keep",
            "Keep",
            List.of(),
            List.of(
                new AgentAction(
                    UUID.randomUUID(),
                    AgentTool.CREATE_DASHBOARD,
                    "Keep",
                    "Keep",
                    true,
                    Map.of("keepReview", true)
                )
            ),
            Instant.now()
        );
        var kept = history.restore(keep, parent, "Bearer current");
        var keptParameters = kept.plan().actions().getFirst().parameters();
        for (String field : List.of("definition", "prepareIntent", "prepareDigest"))
            assertThat(keptParameters.get(field)).isEqualTo(parameters.get(field));
        assertThat(keptParameters.get("operationChanges")).isEqualTo(Map.of());
        assertThat(keptParameters.get("expectedOutcomes")).isEqualTo(List.of());
        assertThat(keptParameters.get("responsiveReflow")).isEqualTo(false);
        // Keep must not trigger restoreTo(), which can supersede later pending goals.
        assertThat(kept.context().context()).doesNotContainKey(
            DashboardReviewHistory.RESTORED_FROM
        );
        assertThat(kept.plan().id()).isEqualTo(keep.id());
        verify(contexts).revalidate(parent.refs(), "Bearer current");
        var failedParent = new PlanStore.Stored(
            parentPlan,
            account,
            1,
            "second",
            Instant.now().plusSeconds(600),
            "NEEDS_INPUT",
            null,
            parent.refs()
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            history.restore(keep, failedParent, "Bearer current")
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var superseded = new PlanStore.Stored(
            parentPlan,
            account,
            1,
            "second",
            parent.expiresAt(),
            "SUPERSEDED",
            null,
            parent.refs()
        );
        var supersededOriginal = new PlanStore.Stored(
            original,
            account,
            1,
            "first",
            old.expiresAt(),
            "SUPERSEDED",
            null,
            old.refs()
        );
        when(store.context(parentPlan.id(), scope)).thenReturn(
            Map.of("v2ParentReviewId", original.id().toString())
        );
        when(store.find(original.id(), scope)).thenReturn(supersededOriginal);
        var v2Restored = history.restore(
            requested,
            superseded,
            "Bearer current",
            "v2ParentReviewId"
        );
        assertThat(v2Restored.plan().actions().getFirst().parameters()).isEqualTo(parameters);
        assertThat(
            v2Restored.context().context().get(DashboardReviewHistory.RESTORED_FROM)
        ).isEqualTo(original.id().toString());
        var v2Kept = history.restore(keep, superseded, "Bearer current", "v2ParentReviewId");
        assertThat(v2Kept.plan().actions().getFirst().parameters().get("definition")).isEqualTo(
            parameters.get("definition")
        );
        assertThat(
            v2Kept.plan().actions().getFirst().parameters().get("operationChanges")
        ).isEqualTo(Map.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            history.restore(keep, superseded, "Bearer current")
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND
            )
        )
            .when(contexts)
            .revalidate(parent.refs(), "Bearer current");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            history.restore(keep, parent, "Bearer current")
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
