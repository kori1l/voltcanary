package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DashboardCreationExecutor {
    private final RestClient dashboards;

    public DashboardCreationExecutor(AiAgentProperties properties, RestClient.Builder builder) {
        dashboards = builder.clone().baseUrl(properties.getServices().getDashboardBaseUrl()).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentAction action, String authorization, String actionKey) {
        var values = action.parameters();
        if (action.tool() == com.voltcanary.aiagent.model.AgentTool.UPDATE_DASHBOARD_DRAFT) {
            return update(action, authorization, actionKey);
        }
        String preparation = values.containsKey("compositionVersion") ? "/dashboards/composition/prepare"
                : "/dashboard-templates/" + values.get("templateId") + "/prepare";
        Map<String, Object> fresh = dashboards.post().uri(preparation)
                .header("Authorization", authorization).body(values.get("prepareIntent")).retrieve().body(Map.class);
        if (fresh == null || !"READY".equals(fresh.get("status")) || !values.get("prepareDigest").equals(fresh.get("digest"))) {
            throw new IllegalArgumentException("The reviewed widgets or bindings changed; prepare again");
        }
        Map<String, Object> created = dashboards.post().uri("/dashboards").header("Authorization", authorization)
                .header("Idempotency-Key", domainKey(actionKey)).body(Map.of("definition", values.get("definition")))
                .retrieve().body(Map.class);
        if (created == null || created.get("id") == null || ((Number) created.get("publishedRevision")).intValue() != 0) {
            throw new IllegalStateException("Domain response did not confirm a private draft");
        }
        return result(created);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> reconcile(AgentAction action, String authorization, String actionKey) {
        if (action.tool() == com.voltcanary.aiagent.model.AgentTool.UPDATE_DASHBOARD_DRAFT) {
            try {
                Map<String, Object> receipt = dashboards.get().uri("/dashboards/{id}/draft/receipt", action.parameters().get("dashboardId"))
                        .header("Authorization", authorization).header("Idempotency-Key", domainKey(actionKey)).retrieve().body(Map.class);
                return updated(action, receipt);
            } catch (org.springframework.web.client.RestClientResponseException missing) {
                if (missing.getStatusCode().value() != 404) throw missing;
                return update(action, authorization, actionKey);
            }
        }
        try {
            Map<String, Object> created = dashboards.get().uri("/dashboards/creation-receipt")
                    .header("Authorization", authorization).header("Idempotency-Key", domainKey(actionKey))
                    .retrieve().body(Map.class);
            if (created == null || created.get("id") == null) throw new IllegalStateException("Creation receipt response is invalid");
            return result(created);
        } catch (org.springframework.web.client.RestClientResponseException missing) {
            if (missing.getStatusCode().value() != 404) throw missing;
            return execute(action, authorization, actionKey);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> update(AgentAction action, String authorization, String actionKey) {
        var values = action.parameters();
        Map<String, Object> receipt = dashboards.put().uri("/dashboards/{id}/draft", values.get("dashboardId"))
                .header("Authorization", authorization).header("If-Match", "\"" + values.get("draftEtag") + "\"")
                .header("Idempotency-Key", domainKey(actionKey))
                .body(Map.of("baseRevision", values.get("baseRevision"), "definition", values.get("definition")))
                .retrieve().body(Map.class);
        return updated(action, receipt);
    }

    private Map<String, Object> updated(AgentAction action, Map<String, Object> receipt) {
        if (receipt == null || !String.valueOf(action.parameters().get("dashboardId")).equals(String.valueOf(receipt.get("dashboardId")))
                || !action.parameters().get("definition").equals(receipt.get("definition"))) throw new IllegalStateException("Draft receipt does not match reviewed update");
        return Map.of("dashboardId", receipt.get("dashboardId"), "draft", receipt, "route", "/dashboards/" + receipt.get("dashboardId") + "/edit", "setupStatus", "PRIVATE_DRAFT_UPDATED");
    }

    private Map<String, Object> result(Map<String, Object> created) {
        return Map.of("dashboardId", created.get("id"), "dashboard", created,
                "route", "/dashboards/" + created.get("id") + "/edit", "setupStatus", "PRIVATE_DRAFT");
    }

    /** The owning dashboard contract caps keys at 128 characters; preserve the full scoped identity by hashing. */
    public static String domainKey(String actionKey) {
        try { return "ai-dashboard-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actionKey.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
