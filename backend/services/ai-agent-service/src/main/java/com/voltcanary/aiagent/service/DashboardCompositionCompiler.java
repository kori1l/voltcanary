package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Resolves model references; the owning dashboard service compiles the real definition. */
final class DashboardCompositionCompiler {
    private static final Set<String> FIELDS = Set.of("name", "period", "deviceRefs", "widgets");
    static final Set<String> PANEL_FIELDS = Set.of("key", "type", "title", "deviceRefs", "metric", "unit", "sourceRef",
            "visualization", "reducer", "text", "width", "height", "period", "showTitle", "showMapDetails", "showMapNotices", "compact", "vehicleField", "settings", "dataRef");
    private final RestClient dashboards;
    private final ObjectMapper mapper;

    DashboardCompositionCompiler(AiAgentProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        dashboards = builder.clone().baseUrl(properties.getServices().getDashboardBaseUrl()).build();
        this.mapper = mapper;
    }

    AgentPlan compile(AgentPlan plan, Prepared context, AgentAction proposed, String authorization) {
        var values = proposed.parameters();
        if (!FIELDS.containsAll(values.keySet())) return question(plan, "Уточните состав виджетов без шаблона или технических полей.");
        List<ResourceRef> devices = resolve(context, "device", values.get("deviceRefs"));
        if (devices == null) return question(plan, "Выберите конкретные доступные устройства для дашборда.");
        if (!(values.get("widgets") instanceof List<?> panels) || panels.isEmpty() || panels.size() > 12) return question(plan, "Выберите от1 до12 виджетов.");
        var widgets = new ArrayList<Map<String, Object>>();
        for (Object item : panels) {
            if (!(item instanceof Map<?, ?> original) || !PANEL_FIELDS.containsAll(original.keySet())) return question(plan, "Модель не смогла подготовить корректные настройки виджетов. Повторите запрос — описание формата от вас не требуется.");
            Map<?, ?> panel = DashboardDataReferences.resolve(original, context);
            for (String flag : List.of("showTitle", "showMapDetails", "showMapNotices", "compact")) {
                if (panel.get(flag) != null && !(panel.get(flag) instanceof Boolean)) {
                    return question(plan, "Модель передала некорректные настройки отображения. Повторите запрос.");
                }
            }
            var widget = new LinkedHashMap<String, Object>();
            for (String key : PANEL_FIELDS) if (!Set.of("deviceRefs", "sourceRef", "dataRef").contains(key) && panel.get(key) != null) widget.put(key, panel.get(key));
            if (panel.get("_resolvedDataChoiceId") != null) widget.put("dataChoiceId", panel.get("_resolvedDataChoiceId"));
            if (panel.get("deviceRefs") != null) {
                var selection = resolve(context, "device", panel.get("deviceRefs"));
                if (selection == null || !devices.stream().map(ResourceRef::resourceId).toList().containsAll(selection.stream().map(ResourceRef::resourceId).toList())) return question(plan, "Устройство виджета должно входить в выбранные устройства дашборда.");
                widget.put("deviceIds", selection.stream().map(ResourceRef::resourceId).toList());
            }
            if (panel.get("sourceRef") != null) {
                var source = context.refs().stream().filter(ref -> ref.kind().equals("source") && ref.ref().equals(panel.get("sourceRef"))).findFirst();
                if (source.isEmpty()) return question(plan, "Выберите источник из текущего каталога устройства.");
                widget.put("sourceId", source.get().resourceId());
            }
            widgets.add(widget);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", values.get("name"));
        request.put("deviceIds", devices.stream().map(ResourceRef::resourceId).toList());
        // Per-widget intervals may differ. Do not collapse all periods in the conversation into one regex match.
        Object period = values.get("period");
        if (period != null && !(period instanceof String)) return question(plan, "Уточните период дашборда.");
        request.put("period", period == null ? "LAST_24_HOURS" : period);
        request.put("widgets", widgets);
        JsonNode response;
        try {
            response = dashboards.post().uri("/dashboards/composition/prepare").header("Authorization", authorization)
                    .body(request).retrieve().body(JsonNode.class);
        } catch (org.springframework.web.client.RestClientResponseException invalid) {
            int status = invalid.getStatusCode().value();
            String category = status == 401 || status == 403 ? "ACCESS" : status == 404 ? "RESOURCE" : status >= 500 ? "DEPENDENCY" : "INVALID_PLAN";
            throw new DashboardPreparationRejected(category, "COMPOSITION_HTTP_" + status,
                    "Доменная подготовка отклонена (HTTP " + status + "). Предыдущий вариант сохранён.", status == 400 || status == 422);
        } catch (RestClientException invalid) {
            throw new DashboardPreparationRejected("DEPENDENCY", "COMPOSITION_UNAVAILABLE", "Доменная подготовка недоступна. Предыдущий вариант сохранён.", false);
        }
        if (response == null || !"READY".equals(response.path("status").asText())) {
            String diagnostics = response == null ? "Подготовка дашборда недоступна." : java.util.stream.StreamSupport.stream(response.path("diagnostics").spliterator(), false)
                    .map(JsonNode::asText).reduce((a, b) -> a + " " + b).orElse("Уточните виджеты дашборда.");
            return question(plan, diagnostics);
        }
        var bindings = new ArrayList<Map<String, Object>>();
        for (JsonNode device : response.path("devices")) for (JsonNode source : device.path("sourceIds")) {
            bindings.add(Map.of("deviceId", device.path("id").asText(), "sourceId", source.asText()));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("compositionVersion", 1);
        parameters.put("prepareIntent", request);
        parameters.put("prepareDigest", response.path("digest").asText());
        parameters.put("definition", mapper.convertValue(response.path("definition"), new TypeReference<Map<String, Object>>() { }));
        parameters.put("deviceNames", devices.stream().map(ref -> ref.summary().get("name")).toList());
        parameters.put("sourceBindings", bindings);
        var action = new AgentAction(proposed.id(), proposed.tool(), "Создать дашборд «" + values.get("name") + "»",
                "Проверьте расположение и данные выбранных виджетов. Будет создан личный черновик.", true, parameters);
        return replace(plan, "Предпросмотр дашборда", (context.context().containsKey("previousDashboardIntent")
                ? plan.explanation() + "\n\nПроверьте обновлённый вариант. Предыдущие варианты доступны в истории."
                : "Проверьте виджеты, их расположение и данные. Изменить макет можно сообщением в чате.") + textHistoryNotice(widgets, context), List.of(action));
    }

    private String textHistoryNotice(List<Map<String, Object>> widgets, Prepared context) {
        boolean text = widgets.stream().anyMatch(widget -> "vehicle-timeline".equals(widget.get("type"))
                && context.refs().stream().anyMatch(ref -> ref.kind().equals("dashboard-data")
                    && "string".equals(ref.summary().get("valueType"))
                    && (ref.resourceId().equals(widget.get("dataChoiceId"))
                        || java.util.Objects.equals(ref.summary().get("key"), widget.get("vehicleField"))
                            && java.util.Objects.equals(ref.summary().get("sourceId"), widget.get("sourceId"))
                            && widget.get("deviceIds") instanceof List<?> ids
                            && ids.equals(List.of(ref.summary().get("deviceId"))))));
        return text ? "\n\nТекстовые значения показаны списком: числовая линия для них недоступна." : "";
    }

    private List<ResourceRef> resolve(Prepared context, String kind, Object supplied) {
        if (!(supplied instanceof List<?> refs) || refs.isEmpty() || refs.size() > 20 || refs.stream().anyMatch(value -> !(value instanceof String))
                || Set.copyOf(refs).size() != refs.size()) return null;
        List<ResourceRef> result = new ArrayList<>();
        for (Object value : refs) {
            var matching = context.refs().stream().filter(ref -> ref.kind().equals(kind) && ref.ref().equals(value)).findFirst();
            if (matching.isEmpty()) return null;
            result.add(matching.get());
        }
        return result.stream().map(ResourceRef::resourceId).distinct().count() == result.size() ? result : null;
    }
    private AgentPlan question(AgentPlan plan, String message) { throw new DashboardPreparationRejected(message); }
    private AgentPlan replace(AgentPlan plan, String summary, String explanation, List<AgentAction> actions) {
        return new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), summary, explanation, plan.safetyNotes(), actions,
                plan.createdAt(), plan.providerType(), plan.providerName(), plan.modelId(), plan.providerId(), plan.modelConfigId(), plan.fallbackUsed());
    }
}
