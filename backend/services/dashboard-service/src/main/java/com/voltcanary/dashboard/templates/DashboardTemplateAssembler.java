package com.voltcanary.dashboard.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class DashboardTemplateAssembler {
    private final ObjectMapper mapper;

    public DashboardDefinition assemble(DashboardTaskTemplates.Template template, String name, String period,
            List<TemplateDeviceCatalogue.Device> devices, Set<String> metricKeys) {
        return assemble(template, name, period, devices, metricKeys, null);
    }

    public DashboardDefinition assemble(DashboardTaskTemplates.Template template, String name, String period,
            List<TemplateDeviceCatalogue.Device> devices, Set<String> metricKeys, List<DashboardTemplateLayout.Panel> panelLayout) {
        var placement = new DashboardTemplateLayout();
        var selected = placement.select(template, panelLayout);
        ObjectNode scope = mapper.createObjectNode().put("mode", "FIXED");
        scope.set("deviceIds", mapper.valueToTree(devices.stream().map(TemplateDeviceCatalogue.Device::id).toList()));
        ObjectNode time = mapper.createObjectNode().put("mode", "RELATIVE").put("preset", period).put("timezone", "UTC");
        ObjectNode context = mapper.createObjectNode().set("scope", scope);
        context.set("time", time);
        List<Widget> widgets = new ArrayList<>();
        List<DashboardTemplateLayout.Panel> sizes = new ArrayList<>();
        for (var panel : selected) {
            if (panel.metric() != null && !metricKeys.isEmpty() && !metricKeys.contains(panel.metric())) continue;
            var measured = template.id().equals("my-devices") && panel.type().equals("latest-value") ? devices : List.of(devices.getFirst());
            for (var device : measured) {
                ObjectNode binding = panel.slot() != null && panel.slot().equals("primary") ? mapper.createObjectNode().put("mode", "FIXED") : scope.deepCopy();
                if (panel.slot() != null && panel.slot().equals("primary")) binding.set("deviceIds", mapper.valueToTree(List.of(device.id())));
                ObjectNode query = mapper.valueToTree(panel.query());
                if (panel.metric() != null) { query.put("metric", panel.metric()); query.put("unit", panel.unit()); }
                if (panel.type().equals("time-series")) {
                    query.set("time", time.deepCopy());
                    query.set("series", mapper.valueToTree(List.of(Map.of("deviceId", device.id(), "metric", panel.metric()))));
                }
                String title = panel.title() + (panel.metric() != null ? " · " + device.name() : "");
                widgets.add(new Widget("template-" + widgets.size(), panel.type(), 1, title.substring(0, Math.min(120, title.length())),
                        binding, query, mapper.createObjectNode().put("showTitle", true), mapper.createObjectNode().put("href", panel.drillDown()), mapper.createObjectNode()));
                sizes.add(panelLayout == null ? null : panelLayout.stream().filter(item -> item.key().equals(panel.key())).findFirst().orElseThrow());
            }
        }
        if (widgets.size() > 12) throw new DashboardFailure("LIMIT_EXCEEDED", "Template expands beyond 12 widgets; select fewer devices");
        if (widgets.isEmpty()) throw new DashboardFailure("VALIDATION_ERROR", "Select at least one compatible panel");
        Map<String, Object> layout = new LinkedHashMap<>();
        for (String breakpoint : List.of("desktop", "tablet", "mobile")) {
            int width = breakpoint.equals("desktop") ? 12 : breakpoint.equals("tablet") ? 6 : 1;
            List<Map<String, Object>> items = new ArrayList<>();
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            for (int i = 0; i < widgets.size(); i++) {
                var size = sizes.get(i);
                int w = placement.width(size, width);
                int h = size == null || size.height() == null ? 4 : size.height();
                if (x + w > width) { y += rowHeight; x = 0; rowHeight = 0; }
                items.add(Map.of("widgetId", widgets.get(i).id(), "x", x, "y", y, "w", w, "h", h));
                x += w;
                rowHeight = Math.max(rowHeight, h);
            }
            layout.put(breakpoint, items);
        }
        return new DashboardDefinition(1, name.trim(), template.task(), context, List.of(), List.copyOf(widgets), mapper.valueToTree(layout));
    }

    public String digest(DashboardDefinition definition) {
        try {
            byte[] body = mapper.writeValueAsBytes(canonical(mapper.valueToTree(definition)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception failure) { throw new IllegalStateException("Cannot encode prepared template", failure); }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new java.util.ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            var array = mapper.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }
}
