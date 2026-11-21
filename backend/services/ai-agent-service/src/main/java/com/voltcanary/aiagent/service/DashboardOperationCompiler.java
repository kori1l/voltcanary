package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.DashboardOperation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure lowering to the existing composition seam. No model, persistence or HTTP calls. */
final class DashboardOperationCompiler {
    record Result(Map<String, Object> intent, Map<String, Set<String>> allowedChanges,
            List<Map<String, Object>> expectedOutcomes, boolean responsiveReflow, Integer restoreSteps) { }
    private static final Set<String> DATA = Set.of("deviceRefs", "sourceRef", "vehicleField", "metric", "unit", "reducer");
    private static final Set<String> PERIODS = Set.of("LAST_1_HOUR", "LAST_6_HOURS", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS");
    private final AuthorizedPlanningContext.Prepared context;
    private final String turnId;
    private final Map<String, String> references = new LinkedHashMap<>();
    private final Map<String, Set<String>> mask = new LinkedHashMap<>();
    private final Set<String> writes = new HashSet<>();
    private final List<Map<String, Object>> outcomes = new ArrayList<>();
    private Map<String, Object> intent;
    private boolean reflow;

    private DashboardOperationCompiler(AuthorizedPlanningContext.Prepared context, String turnId) {
        this.context = context;
        this.turnId = turnId;
        if (context.context().get("previousDashboardIntent") instanceof Map<?, ?> previous) {
            if (!(previous.get("widgets") instanceof List<?>)) throw invalid("Legacy template must first be selected as a composition");
            intent = copy(previous);
        } else {
            intent = new LinkedHashMap<>();
            intent.put("name", context.context().getOrDefault("name", ""));
            intent.put("period", DashboardOperationProtocol.DEFAULT_PERIOD);
            intent.put("deviceRefs", context.context().getOrDefault("selectedDashboardDeviceRefs", List.of()));
            intent.put("widgets", List.of());
        }
    }

    static Result compile(DashboardOperationProtocol.Envelope envelope, AuthorizedPlanningContext.Prepared context, String turnId) {
        var compiler = new DashboardOperationCompiler(context, turnId);
        if (envelope.operations().getFirst() instanceof DashboardOperation.Keep) {
            if (envelope.operations().size() != 1 || !context.context().containsKey("previousDashboardIntent")) {
                throw invalid("Keep requires a selected saved review and no other edits");
            }
            // Zero selects the current review at the authorized saved-history boundary.
            return new Result(Map.of(), Map.of(), List.of(), false, 0);
        }
        if (envelope.operations().getFirst() instanceof DashboardOperation.Restore restore) {
            if (restore.restoreSteps() == null || restore.restoreSteps() < 1 || restore.restoreSteps() > 30) throw invalid("Invalid restoration distance");
            return new Result(Map.of(), Map.of(), List.of(), false, restore.restoreSteps());
        }
        for (var operation : envelope.operations()) compiler.apply(operation);
        int widgetCount = compiler.widgets().size();
        if (widgetCount == 0) {
            throw new DashboardPreparationRejected("INVALID_PLAN", "DASHBOARD_WIDGETS_EMPTY",
                    "Include at least one widget in the resulting dashboard.", true);
        }
        if (widgetCount > 12) {
            throw new DashboardPreparationRejected("INVALID_PLAN", "DASHBOARD_WIDGET_LIMIT_EXCEEDED",
                    "The resulting dashboard has " + widgetCount + " widgets; the limit is 12. Preserve the requested content when resolving the limit.", true);
        }
        if (!(compiler.intent.get("deviceRefs") instanceof List<?> devices) || devices.size() > 20 || devices.isEmpty()) {
            throw invalid("Choose 1–20 devices");
        }
        var frozenMask = new LinkedHashMap<String, Set<String>>();
        compiler.mask.forEach((key, fields) -> frozenMask.put(key, Set.copyOf(fields)));
        compiler.outcomes.addAll(DashboardOperationExpectations.derive(compiler.intent, compiler.mask, context));
        return new Result(Map.copyOf(compiler.intent), Map.copyOf(frozenMask), List.copyOf(compiler.outcomes), compiler.reflow, null);
    }

    private void apply(DashboardOperation operation) {
        switch (operation) {
            case DashboardOperation.ShowMap value -> show(value.id(), value.dataRef(), value.widgetKey(), "MAP", null, null);
            case DashboardOperation.ShowCurrent value -> show(value.id(), value.dataRef(), value.widgetKey(), "CURRENT", null, null);
            case DashboardOperation.ShowHistory value -> show(value.id(), value.dataRef(), value.widgetKey(), "HISTORY", value.view(), value.period());
            case DashboardOperation.AddNote value -> {
                bounded(value.title(), 120);
                if (value.text() == null || value.text().length() > 1000) throw invalid("Invalid note text");
                var panel = new LinkedHashMap<String, Object>(Map.of("key", newKey(value.id()), "type", "context-note",
                        "title", value.title(), "text", value.text(), "width", "FULL", "height", 3));
                add(panel);
                references.put(value.id(), (String) panel.get("key"));
            }
            case DashboardOperation.Place value -> place(value);
            case DashboardOperation.Resize value -> resize(value);
            case DashboardOperation.Presentation value -> {
                if (value.settings() == null || value.settings().isEmpty() || value.settings().size() > 32) throw invalid("Invalid presentation patch");
                var settings = new LinkedHashMap<String, Object>();
                for (var patch : value.settings()) {
                    if (patch == null || patch.key() == null || settings.containsKey(patch.key())) throw invalid("Duplicate or invalid setting patch");
                    settings.put(patch.key(), patch.value());
                }
                for (String key : targets(value.targets(), true)) {
                    validateSettings(widget(key), settings);
                    for (String setting : settings.keySet()) write(key, "settings." + setting);
                    patch(key, Map.of("settings", settings));
                }
            }
            case DashboardOperation.RenameWidget value -> {
                bounded(value.title(), 120);
                String key = target(value.widgetKey());
                write(key, "title");
                patch(key, Map.of("title", value.title()));
            }
            case DashboardOperation.SetData value -> {
                String key = target(value.widgetKey());
                var panel = widget(key);
                var data = resolved(value.dataRef(), String.valueOf(panel.get("type")), (String) panel.get("visualization"));
                for (String field : DATA) write(key, field);
                var edit = new LinkedHashMap<String, Object>();
                edit.put("dataRef", value.dataRef());
                // Resolve now for access/type checks; the ordinary compiler resolves again before preparation.
                if (!Objects.equals(data.get("type"), panel.get("type"))) throw invalid("Data replacement cannot switch role");
                patch(key, edit);
                includeDevices(data);
            }
            case DashboardOperation.Remove value -> {
                for (String key : targets(value.targets(), false)) {
                    if (mask.containsKey(key)) throw invalid("Removal conflicts with another write to " + key);
                    write(key, "removed");
                    var panels = new ArrayList<>(widgets());
                    panels.removeIf(panel -> key.equals(panel.get("key")));
                    intent.put("widgets", panels);
                    reflow = true;
                }
            }
            case DashboardOperation.RenameDashboard value -> {
                bounded(value.name(), 80);
                write("$dashboard", "name");
                intent.put("name", value.name());
            }
            case DashboardOperation.Period value -> {
                period(value.period());
                if (value.targets() == null || value.targets().isEmpty()) {
                    write("$dashboard", "period");
                    intent.put("period", value.period());
                } else for (String key : targets(value.targets(), false)) {
                    write(key, "period");
                    patch(key, Map.of("period", value.period()));
                }
            }
            default -> throw invalid("Unsupported operation");
        }
        outcomes.add(Map.of("operationId", operation.id(), "targets", Map.copyOf(references)));
    }

    private void show(String id, String dataRef, String requestedKey, String role, String view, String period) {
        var choice = context.refs().stream().filter(ref -> ref.kind().equals("dashboard-data") && ref.ref().equals(dataRef))
                .findFirst().orElseThrow(() -> invalid("Expired or unavailable dataRef"));
        if (Boolean.TRUE.equals(choice.summary().get("requiresSourceChoice"))) throw new DashboardSourceChoiceRequired(choice.summary(), context);
        if (!(choice.summary().get("views") instanceof List<?> views)) throw invalid("No supported data view");
        Map<String, Object> existing = requestedKey == null ? null : widget(target(requestedKey));
        var compatible = views.stream().filter(item -> item instanceof Map<?, ?> candidate && role(candidate, role)
                && (existing == null || Objects.equals(existing.get("type"), candidate.get("widgetType")))
                && (view == null || candidate.get("visualizations") instanceof List<?> modes && modes.contains(view)))
                .map(item -> (Map<?, ?>) item).toList();
        if (compatible.isEmpty()) throw invalid("Requested role/view is unavailable; use an offered compatible view");
        // The descriptor orders compatible views by the owning service's preference.
        String type = String.valueOf(compatible.getFirst().get("widgetType"));
        String selectedView = view;
        if (role.equals("HISTORY") && selectedView == null && compatible.getFirst().get("visualizations") instanceof List<?> modes && !modes.isEmpty()) {
            selectedView = String.valueOf(modes.contains("line") ? "line" : modes.getFirst());
        }
        if (period != null) period(period);
        var data = resolved(dataRef, type, selectedView);
        String selectedPeriod = period == null ? String.valueOf(intent.get("period")) : period;
        String requestedView = selectedView;
        var matches = widgets().stream().filter(panel -> type.equals(panel.get("type")) && sameData(panel, data)
                && Objects.equals(panel.getOrDefault("period", intent.get("period")), selectedPeriod)
                && (requestedView == null || requestedView.equals(panel.getOrDefault("visualization", type.equals("vehicle-timeline") ? "list" : "line")))).toList();
        var selected = existing;
        if (selected != null && !sameData(selected, data)) throw invalid("Explicit target has different data; use set_data for replacement");
        if (selected == null) {
            if (matches.size() > 1) throw invalid("Multiple matching widgets; choose an existing widgetKey");
            if (matches.size() == 1) selected = matches.getFirst();
        }
        String key;
        if (selected == null) {
            key = newKey(id);
            var panel = new LinkedHashMap<>(data);
            panel.remove("_resolvedDataChoiceId");
            panel.put("key", key);
            panel.put("title", String.valueOf(choice.summary().getOrDefault("label", choice.summary().getOrDefault("key", "Карта"))));
            panel.put("width", role.equals("CURRENT") ? "THIRD" : "FULL");
            panel.put("height", role.equals("CURRENT") ? 1 : role.equals("MAP") ? 8 : 5);
            if (role.equals("CURRENT")) panel.put("settings", Map.of("compact", true));
            if (period != null) panel.put("period", period);
            add(panel);
        } else {
            key = String.valueOf(selected.get("key"));
            if (view != null && !view.equals(selected.get("visualization"))) {
                write(key, "visualization");
                patch(key, Map.of("visualization", view));
            }
            if (period != null && !Objects.equals(selected.getOrDefault("period", intent.get("period")), period)) {
                write(key, "period");
                patch(key, Map.of("period", period));
            }
        }
        references.put(id, key);
        Object effectivePeriod = selected != null && period == null ? selected.getOrDefault("period", selectedPeriod) : selectedPeriod;
        outcomes.add(Map.of("role", role, "widgetKey", key, "dataChoiceId", choice.resourceId(), "period", effectivePeriod));
    }

    private boolean role(Map<?, ?> view, String role) {
        String type = String.valueOf(view.get("widgetType"));
        return switch (role) {
            case "MAP" -> "vehicle-map".equals(type);
            case "CURRENT" -> Set.of("vehicle-value", "latest-value").contains(type);
            case "HISTORY" -> Set.of("vehicle-timeline", "time-series").contains(type);
            default -> false;
        };
    }

    private Map<String, Object> resolved(String ref, String type, String view) {
        var values = new LinkedHashMap<String, Object>();
        values.put("dataRef", ref);
        values.put("type", type);
        if (view != null) values.put("visualization", view);
        return copy(DashboardDataReferences.resolve(values, context));
    }

    private boolean sameData(Map<String, Object> panel, Map<String, Object> data) {
        // Existing compositions store expanded bindings; never compare expired opaque dataRef tokens.
        Map<String, Object> normalized = copy(DashboardDataReferences.resolve(panel, context));
        return DATA.stream().filter(field -> !field.equals("reducer")).allMatch(field -> Objects.equals(
                normalized.getOrDefault(field, field.equals("deviceRefs") ? intent.get("deviceRefs") : null), data.get(field)));
    }

    private void place(DashboardOperation.Place value) {
        List<String> keys = targets(value.targets(), false);
        if (!Set.of("TOP", "BOTTOM", "BEFORE", "AFTER").contains(value.position() == null ? "" : value.position())) throw invalid("Invalid placement");
        var move = new LinkedHashMap<String, Object>();
        move.put("keys", keys);
        move.put("position", switch (value.position()) { case "TOP" -> "START"; case "BOTTOM" -> "END"; default -> value.position(); });
        if (Set.of("BEFORE", "AFTER").contains(value.position())) {
            String anchor = target(value.anchor());
            if (keys.contains(anchor)) throw invalid("Placement anchor cannot belong to the moved group");
            move.put("anchor", anchor);
        } else if (value.anchor() != null) throw invalid("TOP/BOTTOM cannot include an anchor");
        for (String key : keys) write(key, "order");
        intent = DashboardIntentEdits.apply(Map.of("moveWidgets", move), intent);
        reflow = true;
    }

    private void resize(DashboardOperation.Resize value) {
        if (value.width() == null && value.height() == null && value.heightPercent() == null) throw invalid("Empty size change");
        if (value.width() != null && !Set.of("FULL", "HALF", "THIRD").contains(value.width())) throw invalid("Invalid supported width");
        if (value.height() != null && (value.height() < 1 || value.height() > 12 || value.heightPercent() != null)) throw invalid("Invalid row height");
        if (value.heightPercent() != null && (value.heightPercent() < 1 || value.heightPercent() > 400)) throw invalid("Height percentage must be 1–400");
        for (String key : targets(value.targets(), false)) {
            var edit = new LinkedHashMap<String, Object>();
            if (value.width() != null) {
                write(key, "width");
                edit.put("width", value.width());
            }
            if (value.height() != null || value.heightPercent() != null) {
                write(key, "height");
                int base = ((Number) widget(key).getOrDefault("height", 4)).intValue();
                int height = value.height() == null ? Math.max(1, Math.min(12, Math.round(base * value.heightPercent() / 100f))) : value.height();
                edit.put("height", height);
                outcomes.add(Map.of("widgetKey", key, "height", height, "previousHeight", base, "quantizedToRows", true));
            }
            patch(key, edit);
        }
        reflow = true;
    }

    private void validateSettings(Map<String, Object> widget, Map<String, Object> settings) {
        var descriptor = context.refs().stream().filter(ref -> ref.kind().equals("widget-descriptor") && ref.resourceId().equals(widget.get("type")))
                .findFirst().orElseThrow(() -> invalid("Widget descriptor is unavailable"));
        if (!(descriptor.summary().get("settings") instanceof List<?> offered)) throw invalid("Settings descriptor unavailable");
        for (var entry : settings.entrySet()) {
            var setting = offered.stream().filter(item -> item instanceof Map<?, ?> declared && Objects.equals(declared.get("key"), entry.getKey()))
                    .map(item -> (Map<?, ?>) item).findFirst().orElseThrow(() -> invalid("Unsupported setting " + entry.getKey()));
            Object value = entry.getValue();
            if (value == null) continue;
            String type = String.valueOf(setting.get("type"));
            boolean valid = switch (type) {
                case "BOOLEAN", "AUTO_BOOLEAN" -> value instanceof Boolean;
                case "TEXT" -> value instanceof String text && setting.get("maxLength") instanceof Number max && text.length() <= max.intValue();
                case "SELECT" -> setting.get("choices") instanceof List<?> choices && choices.stream().anyMatch(item -> item instanceof Map<?, ?> choice && Objects.equals(choice.get("value"), value));
                case "FIELD_SET" -> value instanceof List<?> fields && fields.size() <= 50 && fields.stream().allMatch(field -> field instanceof String text && text.matches("[A-Za-z][A-Za-z0-9_.]{0,99}"));
                default -> false;
            };
            if (!valid) throw invalid("Invalid setting type or value");
        }
    }

    private void add(Map<String, Object> panel) {
        String key = String.valueOf(panel.get("key"));
        if (widgets().stream().anyMatch(existing -> key.equals(existing.get("key")))) throw invalid("Generated widget key collision");
        var panels = new ArrayList<>(widgets());
        panels.add(panel);
        intent.put("widgets", panels);
        includeDevices(panel);
        mask.put(key, new LinkedHashSet<>(Set.of("created")));
        reflow = true;
    }

    private void includeDevices(Map<String, Object> panel) {
        // Grow only from server-resolved devices; preserve scope used by unrelated widgets.
        var previousDevices = (List<?>) intent.get("deviceRefs");
        var devices = new LinkedHashSet<Object>(previousDevices);
        int previousSize = devices.size();
        if (panel.get("deviceRefs") instanceof List<?> selected) devices.addAll(selected);
        intent.put("deviceRefs", List.copyOf(devices));
        if (devices.size() != previousSize) {
            // Legacy panels can inherit the root selection. Expanding it must not
            // change their effective binding. Replacement dataRef resolves separately.
            var panels = widgets();
            for (var existing : panels) if (!existing.containsKey("deviceRefs") && !existing.containsKey("dataRef")) {
                existing.put("deviceRefs", List.copyOf(previousDevices));
            }
            intent.put("widgets", panels);
            mask.computeIfAbsent("$dashboard", ignored -> new LinkedHashSet<>()).add("deviceRefs");
        }
    }

    private void patch(String key, Map<String, Object> changes) {
        var edit = new LinkedHashMap<>(changes);
        edit.put("key", key);
        intent = DashboardIntentEdits.apply(Map.of("widgetEdits", List.of(edit)), intent);
    }

    private void write(String key, String field) {
        if (!writes.add(key + ":" + field) || mask.getOrDefault(key, Set.of()).contains("removed")) throw invalid("Conflicting writes to " + key + "." + field);
        mask.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(field);
    }

    private String target(String token) {
        if (token == null) throw invalid("Missing widget target");
        String key = token.startsWith("@") ? references.get(token.substring(1)) : token;
        if (key == null || widgets().stream().noneMatch(panel -> key.equals(panel.get("key")))) throw invalid("Unknown, removed or forward widget reference");
        return key;
    }

    private List<String> targets(List<String> supplied, boolean all) {
        if (supplied == null || supplied.isEmpty() || supplied.size() > 12) throw invalid("Choose 1–12 targets");
        if (all && supplied.equals(List.of("ALL"))) return widgets().stream().map(panel -> String.valueOf(panel.get("key"))).toList();
        List<String> keys = supplied.stream().map(this::target).toList();
        if (new HashSet<>(keys).size() != keys.size()) throw invalid("Duplicate widget targets");
        return keys;
    }

    private Map<String, Object> widget(String key) {
        return widgets().stream().filter(panel -> key.equals(panel.get("key"))).findFirst().orElseThrow(() -> invalid("Widget unavailable"));
    }

    private List<Map<String, Object>> widgets() {
        return ((List<?>) intent.get("widgets")).stream().map(item -> copy((Map<?, ?>) item)).toList();
    }

    private String newKey(String id) {
        return "w_" + UUID.nameUUIDFromBytes((turnId + ":" + id).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static void bounded(String text, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum) throw invalid("Invalid bounded title");
    }

    private static void period(String value) {
        if (value == null || !PERIODS.contains(value)) throw invalid("Unsupported period");
    }

    private static Map<String, Object> copy(Map<?, ?> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> result.put((String) key, value));
        return result;
    }

    private static DashboardPreparationRejected invalid(String message) {
        return DashboardOperationProtocol.invalid(message);
    }
}
