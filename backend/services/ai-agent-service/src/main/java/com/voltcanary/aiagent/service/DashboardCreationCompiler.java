package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DashboardCreationCompiler {
    public record CreateDashboardIntent(String name, String templateRef, int templateVersion, List<String> deviceRefs,
            List<String> sourceRefs, List<String> metricRefs, String period, List<Map<String, Object>> panelLayout) { }
    private static final Set<String> FIELDS = Set.of("name", "templateRef", "templateVersion", "deviceRefs", "sourceRefs", "metricRefs", "period", "panelLayout");
    private static final Pattern UNSAFE = Pattern.compile("(?iu)игнорируй|ignore previous|обойди|bypass|чуж|other account|accountId|ownerId|tenantId|workspaceId|администратор|admin|удали(?!\\s+(панел|событ|график|виджет))|delete(?!\\s+(panel|event|chart|widget))|опубликуй|publish|javascript|\\bSQL\\b|секрет|secret|общий доступ|сделай\\s+по\\s+умолчанию");
    private final RestClient dashboards;
    private final ObjectMapper mapper;
    private final DashboardCompositionCompiler compositions;

    public DashboardCreationCompiler(AiAgentProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        dashboards = builder.clone().baseUrl(properties.getServices().getDashboardBaseUrl()).build();
        this.mapper = mapper;
        this.compositions = new DashboardCompositionCompiler(properties, builder, mapper);
    }

    public AgentPlan compile(AgentPlan plan, Prepared context, boolean requested, String authorization) {
        var creates = plan.actions().stream().filter(action -> action.tool() == AgentTool.CREATE_DASHBOARD).toList();
        if (!requested && creates.isEmpty()) return plan;
        if (UNSAFE.matcher(plan.userMessage()).find()) return question(plan, "Поддерживается один личный черновик без публикации, смены прав или дашборда по умолчанию.");
        if (Pattern.compile("(?iuU)(два|две|несколько|two|[2-9])\\s+(дашборд|dashboard)").matcher(plan.userMessage()).find()) return question(plan, "Выберите один дашборд; каждый черновик подтверждается отдельно.");
        if (creates.isEmpty()) return plan.actions().isEmpty() ? plan : question(plan, "Выберите шаблон и личные устройства для отдельного дашборда.");
        if (creates.size() != 1 || plan.actions().size() != 1) return question(plan, "Подготовьте один дашборд. Создание устройства — отдельная подтверждаемая задача.");
        var proposed = creates.getFirst();
        DashboardOperationCompiler.Result operations = null;
        if (proposed.parameters().containsKey("protocol")) {
            if (!DashboardOperationProtocol.VERSION.equals(context.context().get("dashboardPlanningProtocol"))) throw new DashboardPreparationRejected("Semantic operations are disabled for new planning");
            operations = DashboardOperationCompiler.compile(DashboardOperationProtocol.parse(proposed.parameters(), mapper), context,
                    String.valueOf(context.context().getOrDefault("dashboardTurnId", plan.id().toString())));
            if (operations.restoreSteps() != null) throw new DashboardPreparationRejected("Restore requires an authorized saved review");
            proposed = new AgentAction(proposed.id(), proposed.tool(), proposed.title(), proposed.description(), proposed.requiresConfirmation(), operations.intent());
        } else if (DashboardOperationProtocol.VERSION.equals(context.context().get("dashboardPlanningProtocol"))) {
            throw new DashboardPreparationRejected("Use only dashboard-operations-v1 operations; raw recomposition is not a fallback");
        }
        if (operations == null && (DashboardIntentEdits.incremental(proposed.parameters()) || context.context().containsKey("previousDashboardIntent")
                && !proposed.parameters().containsKey("widgets") && !proposed.parameters().containsKey("templateRef"))) {
            if (!(context.context().get("previousDashboardIntent") instanceof Map<?, ?> previous)) {
                return question(plan, "Сначала подготовьте исходный вариант дашборда.");
            }
            try {
                if (context.context().containsKey("pendingDashboardChange") && DashboardIntentEdits.apply(proposed.parameters(), previous)
                        .equals(DashboardIntentEdits.apply(Map.of(), previous))) {
                    throw new DashboardPreparationRejected("The previous requested change is pending. Empty edits do not implement it; use a compatible widget/presentation for the original pending request.");
                }
                plan = replace(plan, plan.summary(), DashboardIntentEdits.describe(proposed.parameters(), previous), plan.actions());
                proposed = new AgentAction(proposed.id(), proposed.tool(), proposed.title(), proposed.description(),
                        proposed.requiresConfirmation(), DashboardIntentEdits.apply(proposed.parameters(), previous));
            } catch (IllegalArgumentException invalid) {
                throw new DashboardPreparationRejected("Invalid incremental edit: " + invalid.getMessage());
            }
        }
        if (context.context().get("name") instanceof String rememberedName && !rememberedName.isBlank()) {
            Object candidate = proposed.parameters().get("name");
            if (!(candidate instanceof String text) || text.isBlank() || !explicitName(plan.userMessage(), context, text)) {
                var parameters = new LinkedHashMap<>(proposed.parameters());
                parameters.put("name", rememberedName);
                proposed = new AgentAction(proposed.id(), proposed.tool(), proposed.title(), proposed.description(), proposed.requiresConfirmation(), Map.copyOf(parameters));
            }
        }
        if (proposed.parameters().containsKey("widgets")) {
            Object name = proposed.parameters().get("name");
            if (!(name instanceof String text) || text.isBlank() || text.length() > 80 || !explicitName(plan.userMessage(), context, text)) {
                return question(plan, "Как назвать дашборд?", "name", name);
            }
            var compiled = compositions.compile(plan, context, proposed, authorization);
            if (operations == null) return compiled;
            var action = compiled.actions().getFirst();
            var parameters = new LinkedHashMap<>(action.parameters());
            parameters.put("planningProtocol", DashboardOperationProtocol.VERSION);
            parameters.put("operationChanges", operations.allowedChanges());
            parameters.put("expectedOutcomes", operations.expectedOutcomes());
            parameters.put("responsiveReflow", operations.responsiveReflow());
            parameters.put("pendingChangeIds", context.context().getOrDefault("pendingDashboardChangeIds", List.of()));
            String detail = operations.responsiveReflow() ? " Размеры округлены до строк сетки; возможен сдвиг соседних панелей при упаковке." : "";
            return replace(compiled, compiled.summary(), compiled.explanation() + detail, List.of(new AgentAction(action.id(), action.tool(),
                    action.title(), action.description(), action.requiresConfirmation(), parameters)));
        }
        if (context.context().containsKey("dashboardWidgetTypes")) {
            throw new DashboardPreparationRejected("A dashboard composition requires name, deviceRefs and a nonempty widgets list. Do not return empty parameters or select legacy templates. Preserve all original requirements and the known name.");
        }
        // Legacy template commands cannot validate arbitrary metric/source preferences as a composition can.
        if (Pattern.compile("(?iu)несуществующ|неизвестн.*(метрик|шаблон|единиц)|(единиц|метрик|шаблон).*неизвестн|произвольн.*период|(даты|период).*уточн|любые устройства|выбери.*вместо меня|выбери любые|шаблон.*не выбран").matcher(plan.userMessage()).find()) {
            return question(plan, "Уточните поддерживаемый шаблон, метрики, период и конкретные личные устройства.");
        }
        if (!FIELDS.containsAll(proposed.parameters().keySet())) return question(plan, "Разрешены только имя, шаблон, период и ссылки на доступные ресурсы.");
        CreateDashboardIntent intent;
        try {
            intent = mapper.readerFor(CreateDashboardIntent.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue((JsonNode) mapper.valueToTree(proposed.parameters()));
        } catch (java.io.IOException | IllegalArgumentException invalid) { return question(plan, "Уточните имя, версию шаблона и список устройств."); }
        if (intent.name() == null || intent.name().isBlank() || intent.name().length() > 80 || !explicitName(plan.userMessage(), context, intent.name())) return question(plan, "Как назвать дашборд? Укажите имя в кавычках или после «назови».");
        ResourceRef template = resolve(context.refs(), "template", intent.templateRef());
        if (template == null || intent.templateVersion() != ((Number) template.summary().get("version")).intValue()) return question(plan, "Выберите доступный шаблон и его текущую версию из каталога.");
        String templateRequest = latestTemplateRequest(plan.userMessage(), context);
        for (ResourceRef candidate : context.refs()) if (candidate.kind().equals("template") && !candidate.resourceId().equals(template.resourceId())) {
            if (mentionsTemplate(templateRequest, candidate)) {
                return question(plan, "Модель выбрала другой шаблон. Уточните один шаблон из каталога; запрошенный шаблон не заменяется автоматически.");
            }
        }
        List<ResourceRef> devices = resolveAll(context, "device", intent.deviceRefs(), 20, true);
        List<ResourceRef> sources = resolveAll(context, "source", intent.sourceRefs(), 20, false);
        List<ResourceRef> metrics = resolveAll(context, "metric", intent.metricRefs(), 100, false);
        if (devices == null || sources == null || metrics == null) return question(plan, "Выберите 1–20 различных личных устройств и только ссылки из текущего каталога. Неоднозначные имена требуют выбора.");
        var mentionedIds = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(plan.userMessage());
        while (mentionedIds.find()) {
            String id = mentionedIds.group();
            if (context.refs().stream().noneMatch(ref -> ref.resourceId().equalsIgnoreCase(id))) return question(plan, "Указанный ресурс недоступен. Выберите личные ресурсы из каталога.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", intent.templateVersion());
        request.put("name", intent.name());
        request.put("deviceIds", devices.stream().map(ResourceRef::resourceId).toList());
        try {
            var answers = context.context().get("clarificationAnswers") instanceof List<?> values ? values : List.of();
            String period = CreationPeriod.explicitOrProposed(plan.userMessage(), intent.period(), answers);
            if (period != null) request.put("period", period);
        } catch (IllegalArgumentException ambiguous) { return question(plan, "Выберите один относительный период для дашборда."); }
        request.put("metricKeys", metrics.stream().map(ResourceRef::resourceId).distinct().toList());
        request.put("sourceIds", sources.stream().map(ResourceRef::resourceId).toList());
        Object panelLayout = intent.panelLayout();
        if (panelLayout == null && context.context().get("previousDashboardIntent") instanceof Map<?, ?> previous
                && template.ref().equals(previous.get("templateRef"))) panelLayout = previous.get("panelLayout");
        if (panelLayout != null) {
            if (!validPanelLayout(panelLayout, template)) return question(plan, "Выберите существующие панели шаблона, ширину FULL/HALF/THIRD и высоту 1–12 строк.");
            request.put("panelLayout", panelLayout);
        }
        JsonNode response;
        try {
            response = dashboards.post().uri("/dashboard-templates/{id}/prepare", template.resourceId())
                    .header("Authorization", authorization).body(request).retrieve().body(JsonNode.class);
        } catch (RestClientException rejected) { return question(plan, "Шаблон или ресурсы сейчас недоступны. Проверьте доступ и выбор, затем подготовьте план заново."); }
        if (response == null || !"READY".equals(response.path("status").asText())) {
            String diagnostics = response == null ? "Подготовка шаблона недоступна." : java.util.stream.StreamSupport.stream(response.path("diagnostics").spliterator(), false)
                    .map(JsonNode::asText).reduce((a, b) -> a + " " + b).orElse("Уточните шаблон и ресурсы.");
            return question(plan, diagnostics);
        }
        List<String> frozenSources = new ArrayList<>();
        List<Map<String, Object>> sourceBindings = new ArrayList<>();
        for (JsonNode device : response.path("devices")) for (JsonNode source : device.path("sourceIds")) {
            frozenSources.add(source.asText());
            sourceBindings.add(Map.of("deviceId", device.path("id").asText(), "sourceId", source.asText()));
        }
        if (!sources.isEmpty() && !new java.util.HashSet<>(frozenSources).equals(sources.stream().map(ResourceRef::resourceId).collect(java.util.stream.Collectors.toSet()))) {
            return question(plan, "Выберите один однозначный источник для каждого устройства; шаблон не подменяет выбранные источники.");
        }
        request.put("sourceIds", frozenSources);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("templateId", template.resourceId());
        parameters.put("templateVersion", intent.templateVersion());
        parameters.put("templateName", template.summary().get("name"));
        parameters.put("deviceNames", devices.stream().map(ref -> ref.summary().get("name")).toList());
        parameters.put("sourceBindings", List.copyOf(sourceBindings));
        parameters.put("prepareIntent", Map.copyOf(request));
        parameters.put("prepareDigest", response.path("digest").asText());
        parameters.put("definition", mapper.convertValue(response.path("definition"), new TypeReference<Map<String, Object>>() { }));
        String panels = java.util.stream.StreamSupport.stream(response.path("definition").path("widgets").spliterator(), false)
                .map(widget -> widget.path("title").asText()).reduce((a, b) -> a + "; " + b).orElse("");
        var compiled = new AgentAction(proposed.id(), AgentTool.CREATE_DASHBOARD, "Создать личный черновик «" + intent.name() + "»",
                "Шаблон: " + template.summary().get("name") + " v" + intent.templateVersion() + ". Устройства: "
                        + devices.stream().map(ref -> ref.summary().get("name").toString()).reduce((a, b) -> a + ", " + b).orElse("")
                        + ". Период: " + response.path("definition").path("defaultContext").path("time").path("preset").asText()
                        + ". Панели: " + panels + ". Только черновик; открывается обычный редактор.", true, Map.copyOf(parameters));
        return replace(plan, "Личный черновик дашборда", "Проверьте шаблон, устройства, панели и период. Публикация и доступы не изменяются.", List.of(compiled));
    }

    boolean explicitName(String message, Prepared context, String name) {
        String supplied = DashboardTaskName.explicit(message, context.context());
        if (supplied != null) return supplied.equals(name);
        if (name.equals(context.context().get("name"))) return true;
        if (context.context().get("priorSuggestedValues") instanceof Map<?, ?> suggestions && name.equals(suggestions.get("name"))) return true;
        if (context.context().get("clarificationAnswers") instanceof List<?> answers && answers.contains(name)) return true;
        String folded = message.toLowerCase(java.util.Locale.ROOT), value = name.toLowerCase(java.util.Locale.ROOT);
        return folded.contains("«" + value + "»") || folded.contains("\"" + value + "\"")
                || Pattern.compile("(?iu)(назови|названием|именем|named|called)\\s*[:=]?\\s*" + Pattern.quote(name)).matcher(message).find();
    }

    private boolean validPanelLayout(Object value, ResourceRef template) {
        if (!(value instanceof List<?> panels) || panels.isEmpty() || panels.size() > 12) return false;
        Set<String> keys = new java.util.HashSet<>();
        var available = mapper.valueToTree(template.summary().get("panels"));
        for (Object item : panels) {
            if (!(item instanceof Map<?, ?> panel) || !Set.of("key", "width", "height").containsAll(panel.keySet())) return false;
            if (!(panel.get("key") instanceof String key) || !keys.add(key)
                    || java.util.stream.StreamSupport.stream(available.spliterator(), false).noneMatch(candidate -> key.equals(candidate.path("key").asText()))) return false;
            if (panel.get("width") != null && !Set.of("FULL", "HALF", "THIRD").contains(panel.get("width"))) return false;
            Object height = panel.get("height");
            if (height != null && (!(height instanceof Integer || height instanceof Long)
                    || ((Number) height).longValue() < 1 || ((Number) height).longValue() > 12)) return false;
        }
        return true;
    }

    private String latestTemplateRequest(String message, Prepared context) {
        if (context.context().get("clarificationAnswers") instanceof List<?> answers) {
            for (int index = answers.size() - 1; index >= 0; index--) if (answers.get(index) instanceof String answer) {
                boolean ordinal = Pattern.compile("(?iuU)(первый|второй|третий|first|second|third)\\s+(шаблон|template)").matcher(answer).find();
                if (ordinal || context.refs().stream().filter(ref -> ref.kind().equals("template")).anyMatch(ref -> mentionsTemplate(answer, ref))) return answer;
            }
        }
        return message;
    }

    private boolean mentionsTemplate(String message, ResourceRef template) {
        String label = String.valueOf(template.summary().get("name"));
        String prefix = "(?iuU)(шаблон[\\p{L}]*|template|на основе|по|из|используй|выбери|use|choose)\\s+[«\"]?";
        return Pattern.compile(prefix + "(?:" + Pattern.quote(label) + "|" + Pattern.quote(template.resourceId()) + ")(?![\\p{L}\\p{N}_-])")
                .matcher(message).find();
    }

    private List<ResourceRef> resolveAll(Prepared context, String kind, Collection<String> values, int maximum, boolean required) {
        if (values == null || values.isEmpty()) return required ? null : List.of();
        if (values.size() > maximum || values.stream().distinct().count() != values.size()) return null;
        List<ResourceRef> result = new ArrayList<>();
        for (String value : values) { var ref = resolve(context.refs(), kind, value); if (ref == null) return null; result.add(ref); }
        if (result.stream().map(ResourceRef::resourceId).distinct().count() != result.size()) return null;
        return result;
    }

    private ResourceRef resolve(List<ResourceRef> refs, String kind, String value) {
        if (value == null) return null;
        var matches = refs.stream().filter(ref -> ref.kind().equals(kind) && (ref.ref().equals(value) || ref.resourceId().equals(value)
                || value.equalsIgnoreCase(String.valueOf(ref.summary().get("name"))))).toList();
        return matches.stream().map(ResourceRef::resourceId).distinct().count() == 1 ? matches.getFirst() : null;
    }

    private AgentPlan question(AgentPlan plan, String message) { return replace(plan, "Требуется уточнение", message, List.of()); }
    private AgentPlan question(AgentPlan plan, String message, String field, Object suggestedValue) {
        var result = question(plan, message);
        if (!(suggestedValue instanceof String value) || value.isBlank() || value.length() > 80) return result;
        var suggestion = new com.voltcanary.aiagent.model.PlanningQuestion(List.of(field),
                "Используй для поля " + field + " значение «" + value + "». Остальные параметры сохрани.", value, Map.of(field,value));
        return new AgentPlan(result.id(), result.userId(), result.userMessage(), result.summary(), result.explanation(),
                result.safetyNotes(), result.actions(), result.createdAt(), result.providerType(), result.providerName(),
                result.modelId(), result.providerId(), result.modelConfigId(), result.fallbackUsed(), suggestion);
    }
    private AgentPlan replace(AgentPlan plan, String summary, String explanation, List<AgentAction> actions) {
        return new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), summary, explanation, plan.safetyNotes(), actions,
                plan.createdAt(), plan.providerType(), plan.providerName(), plan.modelId(), plan.providerId(), plan.modelConfigId(), plan.fallbackUsed());
    }
}
