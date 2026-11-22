package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DashboardPlanningContext {
    private final RestClient devices;
    private final RestClient dashboards;
    private final PlanningInputGuard guard;
    private final AiAgentProperties properties;

    public DashboardPlanningContext(AiAgentProperties properties, RestClient.Builder builder, PlanningInputGuard guard) {
        this.properties = properties;
        devices = builder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        dashboards = builder.clone().baseUrl(properties.getServices().getDashboardBaseUrl()).build();
        this.guard = guard;
    }

    public boolean requested(String message) {
        return recognizesDashboard(message);
    }

    public static boolean recognizesDashboard(String message) {
        return !DeviceTaskContext.requested(message) && message.matches("(?isu).*(дашборд|dashboard|панель|обзор.*устройств).*" );
    }

    private static Map<String, Object> capabilityHint(JsonNode descriptor) {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", descriptor.path("version").asInt());
        result.put("dataKind", descriptor.path("dataKind").asText());
        result.put("valueTypes", java.util.stream.StreamSupport.stream(descriptor.path("valueTypes").spliterator(), false).map(JsonNode::asText).toList());
        var viewTypes = new LinkedHashMap<String, List<String>>();
        descriptor.path("visualizationValueTypes").fields().forEachRemaining(entry -> viewTypes.put(entry.getKey(),
                java.util.stream.StreamSupport.stream(entry.getValue().spliterator(), false).map(JsonNode::asText).toList()));
        result.put("visualizationValueTypes", viewTypes);
        result.put("binding", Map.of("modes", java.util.stream.StreamSupport.stream(descriptor.path("binding").path("modes").spliterator(), false).map(JsonNode::asText).toList(),
                "maxDevices", descriptor.path("binding").path("maxDevices").asInt(), "sourceRequired", descriptor.path("binding").path("sourceRequired").asBoolean(),
                "metricRequired", descriptor.path("binding").path("metricRequired").asBoolean()));
        var settings = new LinkedHashMap<String, String>();
        for (JsonNode setting : descriptor.path("settings")) {
            String choices = java.util.stream.StreamSupport.stream(setting.path("choices").spliterator(), false)
                    .map(choice -> choice.path("value").asText()).collect(java.util.stream.Collectors.joining("|"));
            settings.put(setting.path("key").asText(), setting.path("type").asText()
                    + "; default=" + setting.path("defaultValue").asText("automatic") + (choices.isEmpty() ? "" : "; choices=" + choices));
        }
        result.put("settings", settings);
        // Keep descriptive and typed owner contracts alongside the compact legacy hint.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        result.put("settingContracts", mapper.convertValue(descriptor.path("settings"), List.class));
        if (descriptor.path("settingsSchema").isObject()) {
            result.put("settingsSchema", mapper.convertValue(descriptor.path("settingsSchema"), Map.class));
        }
        return result;
    }

    public Prepared enrich(Prepared base, Map<String, Object> input, String authorization) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (properties.isDashboardOperationsEnabled()) context.put("dashboardPlanningProtocol", DashboardOperationProtocol.VERSION);
        if (input != null && input.get("dashboardTurnId") instanceof String turn) context.put("dashboardTurnId", turn);
        if (input != null && input.get("conversationSelection") instanceof Map<?, ?> selection) context.put("conversationSelection", selection);
        if (input != null && input.get("pendingDashboardChangeIds") instanceof List<?> ids) context.put("pendingDashboardChangeIds", ids);
        if (base.context().get("name") instanceof String name) context.put("name", name);
        if (base.context().get("priorAssistantQuestion") instanceof String question) context.put("priorAssistantQuestion", question);
        if (base.context().get("clarificationAnswers") instanceof List<?> answers) context.put("clarificationAnswers", answers);
        List<ResourceRef> refs = new ArrayList<>(base.refs());
        List<Map<String, Object>> templates = new ArrayList<>();
        for (JsonNode template : array(read(dashboards, "/dashboard-templates", authorization))) {
            if (!template.path("available").asBoolean()) continue;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", template.path("title").asText());
            summary.put("version", template.path("version").asInt());
            summary.put("period", template.path("period").asText());
            List<Map<String, Object>> panels = new ArrayList<>();
            for (JsonNode panel : template.path("panels")) {
                Map<String, Object> description = new LinkedHashMap<>();
                for (String field : List.of("key", "type", "title", "metric", "unit")) if (panel.hasNonNull(field)) description.put(field, panel.path(field).asText());
                panels.add(description);
            }
            summary.put("panels", panels);
            templates.add(entry(refs, "template", template.path("id").asText(), summary));
        }
        context.put("dashboardTemplates", templates);
        List<Map<String, Object>> widgetTypes = new ArrayList<>();
        for (JsonNode widget : array(read(dashboards, "/dashboards/composition/widget-types", authorization))) {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("type", widget.path("type").asText());
            description.put("title", widget.path("title").asText());
            description.put("description", widget.path("description").asText());
            description.put("typeVersion", widget.path("typeVersion").asInt(1));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String field : List.of("units", "timeBasis", "maxSeries", "maxRows", "maxDetailDevices", "sizeConstraints")) {
                if (widget.has(field)) description.put(field, mapper.convertValue(widget.path(field), Object.class));
            }
            description.put("visualizations", java.util.stream.StreamSupport.stream(widget.path("visualizations").spliterator(), false).map(JsonNode::asText).toList());
            description.put("reducers", java.util.stream.StreamSupport.stream(widget.path("reducers").spliterator(), false).map(JsonNode::asText).toList());
            if (widget.path("capabilities").isObject()) description.put("capabilities", capabilityHint(widget.path("capabilities")));
            if (properties.isDashboardOperationsEnabled()) {
                refs.add(new ResourceRef("widget-" + widget.path("type").asText(), "widget-descriptor", widget.path("type").asText(),
                        Map.of("settings", new com.fasterxml.jackson.databind.ObjectMapper().convertValue(widget.path("capabilities").path("settings"), List.class))));
            }
            widgetTypes.add(description);
        }
        context.put("dashboardWidgetTypes", widgetTypes);
        for (String key : List.of("originalDashboardRequest", "pendingDashboardChange", "compositionRepair")) {
            if (input != null && input.get(key) != null) context.put(key, input.get(key));
        }
        if (input != null && input.get("priorSuggestedValues") instanceof Map<?, ?> suggestions) context.put("priorSuggestedValues", suggestions);
        if (input != null && input.get("dashboardReviewVersions") instanceof List<?> versions) context.put("dashboardReviewVersions", versions);
        Set<UUID> selected = new java.util.LinkedHashSet<>();
        Object supplied = input == null ? null : input.get("deviceIds");
        if (supplied != null) {
            if (!(supplied instanceof Collection<?> ids) || ids.isEmpty() || ids.size() > 20) throw invalid("Choose 1–20 device selections");
            for (Object id : ids) selected.add(uuid(id));
            if (selected.size() != ((Collection<?>) supplied).size()) throw invalid("Select each device once");
        }
        if (base.context().get("deviceId") != null) selected.add(uuid(base.context().get("deviceId")));
        if (selected.size() > 20) throw invalid("At most 20 selected devices");
        List<JsonNode> candidates = new ArrayList<>();
        if (!selected.isEmpty()) for (UUID id : selected) candidates.add(read(devices, "/devices/" + id, authorization));
        else {
            JsonNode values = array(read(devices, "/devices?status=ACTIVE&limit=100&offset=0", authorization));
            if (values.size() == 100 && !array(read(devices, "/devices?status=ACTIVE&limit=100&offset=100", authorization)).isEmpty()) {
                throw invalid("Narrow the dashboard device selection; catalogue exceeds 100 candidates");
            }
            values.forEach(candidates::add);
        }
        List<Map<String, Object>> choices = new ArrayList<>();
        Map<String, String> currentDeviceRefs = new LinkedHashMap<>();
        for (JsonNode device : candidates) {
            String id = device.path("id").asText();
            Map<String, Object> label = Map.of("name", device.path("name").asText(), "type", device.path("type").asText());
            var choice = new LinkedHashMap<>(entry(refs, "device", id, label));
            currentDeviceRefs.put(id, String.valueOf(choice.get("ref")));
            if (!selected.isEmpty()) {
                List<Map<String, Object>> sources = new ArrayList<>();
                for (JsonNode source : array(read(devices, "/devices/" + id + "/data-sources", authorization))) {
                    if (!source.path("enabled").asBoolean(true) || !"ACTIVE".equals(source.path("status").asText("ACTIVE"))) continue;
                    sources.add(entry(refs, "source", source.path("id").asText(), Map.of("deviceId", id, "name", source.path("sourceType").asText())));
                }
                choice.put("sources", sources);
            }
            choices.add(choice);
        }
        context.put("dashboardDevices", choices);
        if (!selected.isEmpty()) new DashboardCapabilityContext(dashboards).enrich(context, refs, List.copyOf(selected), currentDeviceRefs,
                input == null ? Map.of() : input, authorization);
        if (context.get("dashboardDataChoices") instanceof List<?> resolved) for (var device : choices) {
            var metrics = new ArrayList<Map<String, Object>>();
            var fields = new ArrayList<Map<String, Object>>();
            for (Object item : resolved) if (item instanceof Map<?, ?> data && java.util.Objects.equals(device.get("ref"), data.get("deviceRef"))) {
                if ("REGISTERED_NUMERIC_METRIC".equals(data.get("family"))) {
                    metrics.add(entry(refs, "metric", String.valueOf(data.get("key")), Map.of("name", data.get("key"), "unit", data.get("unit"))));
                } else if ("RECORDED_VEHICLE_FIELD".equals(data.get("family")) && data.get("key") != null) {
                    fields.add(Map.of("key", data.get("key"), "label", data.get("label"), "type", data.get("valueType"),
                            "unit", data.get("unit"), "sourceId", data.get("sourceLabel")));
                }
            }
            device.put("metrics", List.copyOf(metrics));
            device.put("fields", List.copyOf(fields));
        }
        context.put("selectedDashboardDeviceRefs", selected.isEmpty() ? List.of()
                : choices.stream().map(choice -> choice.get("ref")).toList());
        if (input != null && input.get("previousDashboardIntent") instanceof Map<?, ?> previous) {
            Map<String, Object> hint = new LinkedHashMap<>();
            for (String key : List.of("name", "period", "panelLayout", "metricKeys", "templateVersion")) {
                if (previous.get(key) != null) hint.put(key, previous.get(key));
            }
            refs.stream().filter(ref -> ref.kind().equals("template") && ref.resourceId().equals(previous.get("templateId")))
                    .findFirst().ifPresent(ref -> hint.put("templateRef", ref.ref()));
            hint.put("deviceRefs", previous.get("deviceIds") instanceof List<?> ids
                    ? ids.stream().map(String::valueOf).map(currentDeviceRefs::get).filter(java.util.Objects::nonNull).toList()
                    : choices.stream().map(choice -> choice.get("ref")).toList());
            if (previous.get("widgets") instanceof List<?> widgets) {
                List<Map<String, Object>> renewed = new ArrayList<>();
                for (Object item : widgets) if (item instanceof Map<?, ?> widget) {
                    var copy = new LinkedHashMap<String, Object>();
                    for (String key : List.of("key", "type", "title", "metric", "unit", "visualization", "reducer", "text", "width", "height", "period", "showTitle", "showMapDetails", "showMapNotices", "compact", "vehicleField", "settings")) {
                        if (widget.get(key) != null) copy.put(key, widget.get(key));
                    }
                    if (widget.get("deviceIds") instanceof List<?> ids) copy.put("deviceRefs", ids.stream().map(String::valueOf).map(currentDeviceRefs::get)
                            .filter(java.util.Objects::nonNull).toList());
                    if (widget.get("sourceId") instanceof String id) refs.stream()
                            .filter(ref -> ref.kind().equals("source") && ref.resourceId().equals(id)).findFirst()
                            .ifPresent(ref -> copy.put("sourceRef", ref.ref()));
                    renewed.add(copy);
                }
                hint.put("widgets", renewed);
            }
            context.put("previousDashboardIntent", Map.copyOf(hint));
        }
        if (properties.isDashboardOperationsEnabled()) DashboardOperationContext.compact(context);
        // The owner contract is larger than untrusted user input. Keep both separately bounded and secret-scanned.
        guard.inspectWidgetCatalogue(context.get("dashboardWidgetTypes"));
        var guardedContext = new LinkedHashMap<>(context);
        guardedContext.remove("dashboardWidgetTypes");
        guard.inspect(guardedContext);
        com.voltcanary.aiagent.trace.AssistantTrace.event("SELECTION", "OBSERVED", null,
                Map.of("selectedDeviceRefs", context.getOrDefault("selectedDashboardDeviceRefs", List.of()),
                        "availableDeviceCount", choices.size(), "resourceIds", refs.stream()
                                .filter(ref -> ref.kind().equals("device") || ref.kind().equals("source"))
                                .map(ref -> Map.of("kind", ref.kind(), "id", ref.resourceId(), "ref", ref.ref())).toList()));
        com.voltcanary.aiagent.trace.AssistantTrace.event("CAPABILITIES", "SUCCEEDED", null,
                Map.of("dashboardDevices", context.getOrDefault("dashboardDevices", List.of()),
                        "selectedDeviceRefs", context.getOrDefault("selectedDashboardDeviceRefs", List.of()),
                        "widgetTypes", context.getOrDefault("dashboardWidgetTypes", List.of())));
        return new Prepared(base.scope(), Map.copyOf(context), List.copyOf(refs));
    }


    private Map<String, Object> entry(List<ResourceRef> refs, String kind, String id, Map<String, Object> summary) {
        var ref = new ResourceRef(UUID.randomUUID().toString(), kind, id, Map.copyOf(summary));
        refs.add(ref);
        var entry = new LinkedHashMap<>(summary);
        entry.put("ref", ref.ref());
        return Map.copyOf(entry);
    }

    private UUID uuid(Object id) {
        try { return UUID.fromString(String.valueOf(id)); }
        catch (IllegalArgumentException invalid) { throw invalid("Invalid device selection"); }
    }

    private JsonNode array(JsonNode values) {
        if (values == null || !values.isArray() || values.size() > 100) throw invalid("Catalogue unavailable or exceeds 100 entries");
        return values;
    }

    private JsonNode read(RestClient client, String path, String authorization) {
        return PlanningContextRead.read(client, path, authorization, false);
    }

    private ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason); }
}
