package com.voltcanary.dashboard.templates;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Authoritative bounded materialization; shared template data and normal validators own semantics. */
@Service
@RequiredArgsConstructor
public class DashboardTemplatePreparation {
    public record Request(int version, String name, List<UUID> deviceIds, String period,
            List<String> metricKeys, List<UUID> sourceIds, List<DashboardTemplateLayout.Panel> panelLayout) {
        public Request(int version, String name, List<UUID> deviceIds, String period, List<String> metricKeys, List<UUID> sourceIds) {
            this(version, name, deviceIds, period, metricKeys, sourceIds, null);
        }
    }
    public record Prepared(String status, String templateId, int templateVersion, String digest,
            DashboardDefinition definition, List<String> diagnostics, List<Map<String, Object>> devices) { }
    private final DashboardTaskTemplates templates;
    private final DashboardOwnership ownership;
    private final TemplateDeviceCatalogue catalogue;
    private final TemplateDefinitionChecks checks;
    private final DashboardTemplateAssembler assembler;

    public Prepared prepare(DashboardActor actor, String authorization, String templateId, Request request) {
        var template = templates.catalog().stream().filter(item -> item.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Template is unavailable"));
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 80
                || request.deviceIds() == null || request.deviceIds().isEmpty() || request.deviceIds().size() > 20
                || request.deviceIds().stream().anyMatch(java.util.Objects::isNull) || Set.copyOf(request.deviceIds()).size() != request.deviceIds().size()
                || request.metricKeys() != null && (request.metricKeys().size() > 100 || request.metricKeys().stream().anyMatch(java.util.Objects::isNull))
                || request.sourceIds() != null && (request.sourceIds().size() > 20 || request.sourceIds().stream().anyMatch(java.util.Objects::isNull))) {
            throw new DashboardFailure("LIMIT_EXCEEDED", "Choose a name and 1–20 distinct personal devices");
        }
        if (!template.available() || template.version() != request.version()) return question(template, "Версия шаблона изменилась или недоступна. Подготовьте новый план.");
        var panels = new DashboardTemplateLayout().select(template, request.panelLayout());
        String period = request.period() == null ? template.period() : request.period();
        if (!Set.of("LAST_1_HOUR", "LAST_6_HOURS", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS").contains(period)) {
            return question(template, "Выберите поддерживаемый относительный период.");
        }
        // Check the complete selection before fetching/disclosing any resource labels.
        for (UUID id : request.deviceIds()) if (!ownership.canReadDevice(authorization, id, actor)) {
            throw new DashboardFailure("NO_ACCESS", "Template binding is unavailable");
        }
        List<TemplateDeviceCatalogue.Device> devices = request.deviceIds().stream().map(id -> catalogue.read(authorization, id)).toList();
        List<String> issues = new ArrayList<>();
        if (template.id().equals("refrigeration") && devices.stream().anyMatch(device -> !device.type().equals("FRIDGE"))) {
            issues.add("Шаблон Refrigeration требует холодильники. Выберите совместимые устройства.");
        }
        if (panels.stream().anyMatch(panel -> Set.of("energy-period", "estimated-cost", "peak-and-ranking", "uptime-or-cycle").contains(panel.type()))) {
            issues.add("Для этого шаблона подтвердите физические счётчики, тарифы или производственные периоды в обычном редакторе.");
        }
        Set<String> expectedMetrics = panels.stream().map(DashboardTaskTemplates.Panel::metric).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (request.metricKeys() != null && !expectedMetrics.containsAll(request.metricKeys())) issues.add("Метрика не входит в выбранный шаблон. Выберите другой шаблон или настройте виджеты в редакторе.");
        if (request.panelLayout() != null && request.metricKeys() != null && !request.metricKeys().isEmpty()
                && !request.metricKeys().containsAll(expectedMetrics)) issues.add("Выбранные панели и список метрик расходятся. Уточните состав панелей; панели не удаляются автоматически.");
        var sources = devices.stream().flatMap(device -> device.sources().stream()).map(TemplateDeviceCatalogue.Source::id).collect(java.util.stream.Collectors.toSet());
        if (request.sourceIds() != null && !sources.containsAll(request.sourceIds())) throw new DashboardFailure("NO_ACCESS", "Source selection is unavailable");
        if (!expectedMetrics.isEmpty() && devices.stream().anyMatch(device -> device.sources().size() != 1)) {
            issues.add("Для стандартных измерительных панелей выберите оборудование с одним однозначным зарегистрированным источником; source-bound переменные настраиваются в обычном редакторе.");
        }
        for (var panel : panels) {
            if (panel.metric() == null) continue;
            if (request.metricKeys() != null && !request.metricKeys().isEmpty() && !request.metricKeys().contains(panel.metric())) continue;
            List<TemplateDeviceCatalogue.Device> measured = template.id().equals("my-devices") && panel.type().equals("latest-value") ? devices : List.of(devices.getFirst());
            for (var device : measured) if (device.metrics().stream().noneMatch(metric -> metric.name().equals(panel.metric()) && metric.unit().equals(panel.unit()))) {
                issues.add(device.name() + ": недоступна метрика " + panel.metric() + " (" + panel.unit() + "). Выберите совместимое оборудование.");
            }
        }
        if (!issues.isEmpty()) return new Prepared("NEEDS_INPUT", template.id(), template.version(), null, null, List.copyOf(issues), List.of());
        DashboardDefinition definition = assembler.assemble(template, request.name(), period, devices,
                request.metricKeys() == null ? Set.of() : Set.copyOf(request.metricKeys()), request.panelLayout());
        checks.validate(actor, authorization, definition);
        List<Map<String, Object>> labels = devices.stream().map(device -> Map.<String, Object>of("id", device.id(), "name", device.name(), "type", device.type(),
                "sourceIds", device.sources().stream().map(TemplateDeviceCatalogue.Source::id).toList())).toList();
        return new Prepared("READY", template.id(), template.version(), assembler.digest(definition), definition,
                List.of("Личный черновик. Публикация, доступы и дашборд по умолчанию не изменяются. Отсутствующие наблюдения отображаются как no data."), labels);
    }

    private Prepared question(DashboardTaskTemplates.Template template, String message) {
        return new Prepared("NEEDS_INPUT", template.id(), template.version(), null, null, List.of(message), List.of());
    }

}
