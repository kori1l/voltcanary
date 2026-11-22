package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Ordinary authorized domain reads. Capability matrices remain owned by dashboard-service. */
public final class ScopedDomainContextLoaders {
    public record Definition(String id, String argumentsSchemaId, JsonNode argumentsSchema, ContextSources.Loader loader) { }
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final AuthorizedPlanningContext authority;
    private final RestClient devices;
    private final RestClient dashboards;
    private final Clock clock;
    public ScopedDomainContextLoaders(ObjectMapper mapper, ProtocolValidator validator, AuthorizedPlanningContext authority,
            AiAgentProperties properties, RestClient.Builder builder, Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.authority = authority;
        this.devices = builder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.dashboards = builder.clone().baseUrl(properties.getServices().getDashboardBaseUrl()).build();
        this.clock = clock;
    }
    public List<Definition> definitions() {
        return List.of(definition("device.catalogue", Map.of("search", string(120)), List.of()),
                definition("device.types", Map.of(), List.of()), definition("location.catalogue", Map.of("search", string(120)), List.of()),
                definition("device.read", Map.of("deviceId", uuid()), List.of("deviceId")),
                definition("source.list", Map.of("deviceId", uuid()), List.of("deviceId")),
                definition("dashboard.read", Map.of("dashboardId", uuid()), List.of("dashboardId")),
                definition("dashboard.widgetTypes", Map.of(), List.of()),
                definition("dashboard.capabilities", Map.of("deviceIds", ((ObjectNode) array(uuid(), 20)).put("minItems", 1).put("uniqueItems", true), "period", enumeration("LAST_1_HOUR", "LAST_6_HOURS", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS"),
                        "purpose", enumeration("CURRENT", "HISTORY", "MAP", "DOMAIN")), List.of("deviceIds")));
    }
    private Definition definition(String id, Map<String, JsonNode> fields, List<String> required) {
        JsonNode argumentsSchema = object(fields, required);
        var resultSchema = resultSchema(id);
        var loader = new ContextSources.Loader() {
            public String id() { return id; }
            public JsonNode resultSchema() { return resultSchema.deepCopy(); }
            public ContextSources.Page load(IntentPlans.ModelInput input, Session session, JsonNode arguments, ContextSources.Position position) {
                if (position != null) throw IntentPlans.reject("CONTEXT_CURSOR_UNSUPPORTED");
                if (!session.owner().equals(authority.scope(session.user(), session.authorization()))) throw new IntentPlans.Rejected(403, "CONTEXT_SCOPE_CHANGED");
                validator.validateRegistered(argumentsSchema, arguments);
                var data = fetch(id, arguments, session);
                validator.validateRegistered(resultSchema, data);
                return new ContextSources.Page(SkillRegistry.digest(data.toString()), clock.instant(), data, null);
            }
        };
        return new Definition(id, id + ":args:v1", argumentsSchema, loader);
    }
    private JsonNode fetch(String id, JsonNode args, Session session) {
        JsonNode response;
        if (id.equals("dashboard.capabilities")) response = read(dashboards, "/dashboards/composition/capabilities", args, session);
        else if (id.equals("dashboard.widgetTypes")) response = read(dashboards, "/dashboards/composition/widget-types", null, session);
        else if (id.equals("dashboard.read")) response = read(dashboards, "/dashboards/" + UUID.fromString(args.path("dashboardId").asText()), null, session);
        else if (id.equals("source.list")) response = read(devices, "/devices/" + UUID.fromString(args.path("deviceId").asText()) + "/data-sources", null, session);
        else if (id.equals("device.read")) response = read(devices, "/devices/" + UUID.fromString(args.path("deviceId").asText()), null, session);
        else if (id.equals("device.types")) response = read(devices, "/device-types", null, session);
        else {
            String path = id.equals("device.catalogue") ? "/devices" : "/locations";
            String query = org.springframework.web.util.UriComponentsBuilder.fromPath(path).queryParam("limit", 101)
                    .queryParamIfPresent("search", java.util.Optional.ofNullable(args.has("search") ? args.path("search").asText() : null)).build().encode().toUriString();
            response = read(devices, query, null, session);
        }
        var result = mapper.createObjectNode();
        var items = result.putArray("items");
        var diagnostics = result.putArray("diagnostics");
        JsonNode values;
        if (id.equals("dashboard.capabilities")) {
            if (!response.path("snapshotId").isTextual() || !response.path("diagnostics").isArray()) throw unavailable();
            values = response.path("choices");
            for (var diagnostic : response.path("diagnostics")) diagnostics.add(select(diagnostic, List.of("code", "message", "deviceId", "key", "choiceIds")));
        } else if (id.endsWith(".read")) values = mapper.createArrayNode().add(response);
        else values = response;
        if (!values.isArray()) throw unavailable();
        if (values.size() > 100) throw IntentPlans.reject("CONTEXT_CANDIDATES_REQUIRE_NARROWING");
        for (var value : values) {
            var properties = select(value, fields(id));
            String identity = id.equals("dashboard.read") ? value.at("/dashboard/id").asText() : value.path(identityField(id)).asText();
            if (identity.isBlank()) throw unavailable();
            String label = value.path("name").asText(value.path("label").asText(value.path("title").asText(identity)));
            if (label.isBlank()) label = identity;
            // Reference is an identity hint, never access authority. Every use must reauthorize.
            var choice = items.addObject().put("ref", SkillRegistry.digest(id + ":" + identity)).put("kind", id).put("label", label);
            choice.set("properties", properties);
        }
        return result;
    }
    private JsonNode read(RestClient client, String path, JsonNode body, Session session) {
        try {
            JsonNode response = body == null ? client.get().uri(path).header("Authorization", session.authorization()).retrieve().body(JsonNode.class)
                    : client.post().uri(path).header("Authorization", session.authorization()).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw unavailable();
            validator.parse(response.toString());
            return response;
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 404 || status == 403) throw new IntentPlans.Rejected(404, "CONTEXT_RESOURCE_UNAVAILABLE");
            if (status == 422) throw IntentPlans.reject("CONTEXT_CANDIDATES_REQUIRE_NARROWING");
            throw unavailable();
        } catch (RestClientException failure) { throw unavailable(); }
    }
    private List<String> fields(String id) {
        return switch (id) {
            case "source.list" -> List.of("id", "deviceId", "sourceType", "protocol", "externalId", "homeRegion");
            case "device.types" -> List.of("code", "name", "displayName", "description");
            case "location.catalogue" -> List.of("id", "name", "description", "parentId");
            case "dashboard.read" -> List.of("dashboard", "definition", "revision", "draftEtag", "permissions", "unsupportedWidgetIds");
            case "dashboard.widgetTypes" -> List.of("type", "typeVersion", "title", "reducers", "visualizations", "capabilities");
            case "dashboard.capabilities" -> List.of("choiceId", "deviceId", "sourceId", "externalSourceId", "family", "key", "label", "valueType", "unit",
                    "currentStatus", "sourceFreshness", "historyStatus", "recordedAt", "views");
            default -> List.of("id", "name", "type", "manufacturer", "model", "locationId", "status");
        };
    }
    private String identityField(String id) {
        return switch (id) {
            case "device.types" -> "code";
            case "dashboard.widgetTypes" -> "type";
            case "dashboard.capabilities" -> "choiceId";
            default -> "id";
        };
    }
    private ObjectNode select(JsonNode value, List<String> fields) {
        if (!value.isObject()) throw unavailable();
        var result = mapper.createObjectNode();
        fields.forEach(field -> { if (value.has(field)) result.set(field, value.get(field)); });
        return result;
    }
    private JsonNode resultSchema(String id) {
        var properties = new LinkedHashMap<String, JsonNode>();
        for (String field : fields(id)) properties.put(field, mapper.createObjectNode());
        var choice = object(Map.of("ref", string(160), "kind", string(40), "label", string(255), "properties", object(properties, List.of())), List.of("ref", "kind", "label", "properties"));
        var diagnostic = object(Map.of("code", string(160), "message", string(2000), "deviceId", mapper.createObjectNode(), "key", mapper.createObjectNode(),
                "choiceIds", array(string(160), 100)), List.of("code", "message"));
        return object(Map.of("items", array(choice, 100), "diagnostics", array(diagnostic, 100)), List.of("items", "diagnostics"));
    }
    private JsonNode object(Map<String, JsonNode> fields, List<String> required) {
        var result = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var properties = result.putObject("properties");
        fields.forEach(properties::set);
        var needs = result.putArray("required");
        required.forEach(needs::add);
        return result;
    }
    private JsonNode string(int max) { return mapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", max); }
    private JsonNode uuid() { return ((ObjectNode) string(36)).put("format", "uuid"); }
    private JsonNode array(JsonNode items, int max) {
        var result = mapper.createObjectNode().put("type", "array").put("maxItems", max);
        result.set("items", items);
        return result;
    }
    private JsonNode enumeration(String... values) {
        var result = mapper.createObjectNode().put("type", "string");
        var allowed = result.putArray("enum");
        for (String value : values) allowed.add(value);
        return result;
    }
    private static IntentPlans.Rejected unavailable() { return new IntentPlans.Rejected(503, "CONTEXT_DEPENDENCY_UNAVAILABLE"); }
}
