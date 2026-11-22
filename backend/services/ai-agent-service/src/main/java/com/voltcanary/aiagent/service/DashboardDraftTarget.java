package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/** Fresh owning-domain draft identity. No model IDs, reconstruction or duplicate composition path. */
final class DashboardDraftTarget {

    private final RestClient dashboards;
    private final ObjectMapper mapper;
    private final PlanStore reviews;

    DashboardDraftTarget(
        RestClient.Builder builder,
        String baseUrl,
        ObjectMapper mapper,
        PlanStore reviews
    ) {
        this.dashboards = builder.clone().baseUrl(baseUrl).build();
        this.mapper = mapper;
        this.reviews = reviews;
    }

    Map<String, Object> load(PlanStore.Stored base, String authorization) {
        if (base == null || base.plan().actions().size() != 1) throw IntentPlans.reject(
            "EXECUTED_DASHBOARD_BASE_REQUIRED"
        );
        var action = base.plan().actions().getFirst();
        String id = null;
        if (action.tool() == AgentTool.UPDATE_DASHBOARD_DRAFT) id = String.valueOf(
            action.parameters().get("dashboardId")
        );
        if (base.status().equals("SUCCESS")) {
            id = savedId(base);
        }
        if (id == null) throw IntentPlans.reject("EXECUTED_DASHBOARD_BASE_REQUIRED");
        id = UUID.fromString(id).toString();
        JsonNode draft = PlanningContextRead.read(
            dashboards,
            "/dashboards/" + id + "/draft",
            authorization,
            true
        );
        if (
            !id.equals(draft.path("dashboardId").asText()) ||
            !draft.path("definition").isObject() ||
            !draft.path("etag").isTextual()
        ) throw IntentPlans.reject("DASHBOARD_DRAFT_INVALID");
        if (base.status().equals("SUCCESS")) {
            if (
                !mapper
                    .valueToTree(action.parameters().get("definition"))
                    .equals(draft.path("definition"))
            ) throw IntentPlans.conflict("DASHBOARD_BASE_CHANGED_REVIEW_CURRENT_DRAFT");
        } else if (
            !String.valueOf(action.parameters().get("draftEtag")).equals(
                draft.path("etag").asText()
            )
        ) {
            throw IntentPlans.conflict("DASHBOARD_DRAFT_VERSION_CHANGED");
        }
        return Map.of(
            "dashboardId",
            id,
            "draftEtag",
            draft.path("etag").asText(),
            "baseRevision",
            draft.path("baseRevision").asInt()
        );
    }

    String savedId(PlanStore.Stored review) {
        if (!review.status().equals("SUCCESS") || review.plan().actions().size() != 1) return null;
        var action = review.plan().actions().getFirst();
        if (
            action.tool() != AgentTool.CREATE_DASHBOARD &&
            action.tool() != AgentTool.UPDATE_DASHBOARD_DRAFT
        ) return null;
        return reviews
            .actionResults(review.plan())
            .stream()
            .filter(
                receipt ->
                    receipt.actionId().equals(action.id()) && receipt.status().equals("SUCCESS")
            )
            .map(receipt -> receipt.result().get("dashboardId"))
            .filter(id -> id instanceof String || id instanceof UUID)
            .map(id -> UUID.fromString(id.toString()).toString())
            .findFirst()
            .orElseThrow(() -> IntentPlans.reject("DASHBOARD_RECEIPT_REQUIRED"));
    }
}
