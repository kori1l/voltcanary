package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthorizedPlanningContext {
    public record ResourceRef(String ref, String kind, String resourceId, Map<String, Object> summary) { }
    public record Scope(UUID userId, UUID accountId) { }
    public record Prepared(Scope scope, Map<String, Object> context, List<ResourceRef> refs) { }
    private final RestClient devices;
    private final RestClient alerts;
    private final RestClient dashboards;
    private final PlanningInputGuard guard;
    private final AiAgentProperties properties;

    public AuthorizedPlanningContext(AiAgentProperties properties, RestClient.Builder builder, PlanningInputGuard guard) {
        devices = builder.baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        alerts = builder.baseUrl(properties.getServices().getAlertBaseUrl()).build();
        dashboards = builder.baseUrl(properties.getServices().getDashboardBaseUrl()).build();
        this.guard = guard;
        this.properties = properties;
    }

    public Scope scope(AuthenticatedUser user, String authorization) {
        JsonNode account = read(devices, "/accounts/current/scope", authorization);
        if (account == null || !user.userId().toString().equals(account.path("userId").asText())
                || !"ACTIVE".equals(account.path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active owned account required");
        }
        return new Scope(user.userId(), UUID.fromString(account.path("id").asText()));
    }

    public Prepared prepare(AuthenticatedUser user, String authorization, String message, Map<String, Object> supplied) {
        guard.validate(message, supplied);
        Scope scope = scope(user, authorization);
        Map<String, Object> context = new LinkedHashMap<>();
        List<ResourceRef> refs = new ArrayList<>();
        Map<String, Object> input = supplied == null ? Map.of() : supplied;
        if (input.get("originalDeviceRequest") instanceof String original) context.put("originalDeviceRequest", original);
        if (properties.isDeviceSetupEnabled()) context.put("deviceSetupCapabilities", List.of("HSL_HFP"));
        for (String key : Set.of("name", "type", "manufacturer", "model", "profile", "protocol", "preset",
                "sourceType", "externalId", "sourceId", "metric", "from", "to", "yamlConfig", "startImmediately", "priorAssistantQuestion")) {
            Object value = input.get(key);
            if (value instanceof String || value instanceof Boolean || value instanceof Number) context.put(key, value);
        }
        if (input.get("clarificationAnswers") instanceof List<?> answers) context.put("clarificationAnswers", List.copyOf(answers));
        for (String kind : List.of("device", "location", "alert")) {
            Object candidate = input.get(kind + "Id");
            if (candidate == null) continue;
            UUID id;
            try { id = UUID.fromString(candidate.toString()); }
            catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource selection"); }
            JsonNode resource = read(kind.equals("alert") ? alerts : devices,
                    "/" + kind + "s/" + id, authorization);
            Map<String, Object> summary = summary(resource);
            ResourceRef ref = new ResourceRef(UUID.randomUUID().toString(), kind, id.toString(), summary);
            refs.add(ref);
            context.put(kind + "Id", id.toString());
            context.put(kind, Map.of("ref", ref.ref(), "name", summary.getOrDefault("name", "")));
        }
        // Candidate summaries are fetched through the caller's domain APIs, never taken from browser names.
        context.put("deviceTypes", catalogue(read(devices, "/device-types", authorization), "type", "code", refs));
        context.put("locations", catalogue(read(devices, "/locations", authorization), "location", "id", refs));
        guard.inspect(context);
        com.voltcanary.aiagent.trace.AssistantTrace.event("CONTEXT", "SUCCEEDED", null, Map.of("selectedResources", refs,
                "selectedDeviceId", String.valueOf(input.get("deviceId")), "contextFields", context.keySet()));
        return new Prepared(scope, Map.copyOf(context), List.copyOf(refs));
    }

    public void revalidate(List<ResourceRef> refs, String authorization) {
        JsonNode widgetTypes = null;
        var checkedPaths = new java.util.HashSet<String>();
        for (ResourceRef ref : refs) {
            if (List.of("type", "template", "metric").contains(ref.kind())) continue;
            if (ref.kind().equals("widget-descriptor")) {
                if (widgetTypes == null) widgetTypes = read(dashboards, "/dashboards/composition/widget-types", authorization);
                JsonNode descriptor = null;
                if (widgetTypes != null && widgetTypes.isArray()) {
                    for (JsonNode candidate : widgetTypes) {
                        if (ref.resourceId().equals(candidate.path("type").asText())) descriptor = candidate;
                    }
                }
                if (descriptor == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Widget descriptor unavailable");
                if (!descriptor.path("capabilities").path("settings").equals(
                        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(ref.summary().get("settings")))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Widget descriptor changed");
                }
                continue;
            }
            if (ref.kind().equals("dashboard-data")) {
                Object device = ref.summary().get("deviceId");
                if (device == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard data selection unavailable");
                readOnce(devices, "/devices/" + device, authorization, checkedPaths);
                if (ref.summary().get("sourceId") != null) readOnce(devices, "/devices/" + device + "/data-sources/" + ref.summary().get("sourceId"), authorization, checkedPaths);
                continue;
            }
            if (ref.kind().equals("source")) {
                readOnce(devices, "/devices/" + ref.summary().get("deviceId") + "/data-sources/" + ref.resourceId(), authorization, checkedPaths);
                continue;
            }
            if (ref.kind().equals("dashboard")) {
                readOnce(dashboards, "/dashboards/" + ref.resourceId(), authorization, checkedPaths);
                continue;
            }
            if (ref.kind().equals("dataset")) {
                readOnce(devices, "/data/datasets/" + UUID.fromString(ref.resourceId()), authorization, checkedPaths);
                continue;
            }
            if (ref.kind().equals("native-review")) {
                var value = read(devices, "/data/reviews/" + UUID.fromString(ref.resourceId()), authorization);
                if (!ref.resourceId().equals(value.path("reviewId").asText())
                        || !String.valueOf(ref.summary().get("reviewDigest")).equals(value.path("reviewDigest").asText())
                        || !String.valueOf(ref.summary().get("revision")).equals(value.path("revision").asText())
                        || !String.valueOf(ref.summary().get("datasetId")).equals(value.path("resourceId").asText())
                        || !String.valueOf(ref.summary().get("receiptId")).equals(value.path("receiptId").asText())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Native owner review changed");
                }
                continue;
            }
            readOnce(ref.kind().equals("alert") ? alerts : devices,
                    "/" + ref.kind() + "s/" + ref.resourceId(), authorization, checkedPaths);
        }
    }

    private void readOnce(RestClient client, String path, String authorization, Set<String> checkedPaths) {
        if (checkedPaths.add(path)) read(client, path, authorization);
    }

    public boolean hasDeviceName(String name, String authorization) {
        JsonNode matches;
        try {
            matches = devices.get().uri(uri -> uri.path("/devices").queryParam("search", name)
                    .queryParam("limit", 100).build()).header("Authorization", authorization).retrieve().body(JsonNode.class);
        } catch (RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Duplicate-name review unavailable");
        }
        if (matches == null || !matches.isArray()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device catalogue unavailable");
        for (JsonNode device : matches) if (name.equalsIgnoreCase(device.path("name").asText())) return true;
        return false;
    }

    public void revalidateCreatedDevices(com.voltcanary.aiagent.dto.ExecutePlanResponse execution, String authorization) {
        if (execution == null) return;
        for (var action : execution.actions()) {
            Object id = action.result().get("deviceId");
            if (id != null) {
                try { UUID.fromString(id.toString()); }
                catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Recorded resource identity is invalid"); }
                read(devices, "/devices/" + id, authorization);
            }
            Object dashboardId = action.result().get("dashboardId");
            if (dashboardId != null) {
                try { UUID.fromString(dashboardId.toString()); }
                catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Recorded resource identity is invalid"); }
                read(dashboards, "/dashboards/" + dashboardId, authorization);
            }
        }
    }

    private List<Map<String, Object>> catalogue(JsonNode values, String kind, String idField, List<ResourceRef> refs) {
        if (values == null || !values.isArray()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalogue unavailable");
        if (values.size() > 100) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Narrow the catalogue to at most 100 choices");
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode value : values) {
            String id = value.path(idField).asText();
            if (id.isBlank()) continue;
            ResourceRef ref = new ResourceRef(UUID.randomUUID().toString(), kind, id, summary(value));
            refs.add(ref);
            Map<String, Object> entry = new LinkedHashMap<>(ref.summary());
            entry.put("ref", ref.ref());
            if (kind.equals("type")) entry.put("code", id);
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> summary(JsonNode value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("name", "displayName", "type", "code")) {
            if (value.hasNonNull(key) && value.path(key).isTextual()) {
                String text = value.path(key).asText();
                if (text.length() > 255) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Resource label exceeds limit");
                result.put(key, text);
            }
        }
        return Map.copyOf(result);
    }

    private JsonNode read(RestClient client, String path, String authorization) {
        return PlanningContextRead.read(client, path, authorization, true);
    }
}
