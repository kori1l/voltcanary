package com.voltcanary.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies explicit changes; absence never means removal or a reset to defaults. */
final class DashboardIntentEdits {
    private static final Set<String> FIELDS = Set.of("name", "period", "deviceRefs", "widgetEdits", "addWidgets", "widgetOrder", "moveWidgets");
    private static final Set<String> CLEARABLE = Set.of("metric", "unit", "reducer", "vehicleField", "sourceRef", "visualization", "text", "period");
    private DashboardIntentEdits() { }
    static boolean incremental(Map<String, Object> values) {
        return values.containsKey("widgetEdits") || values.containsKey("addWidgets") || values.containsKey("widgetOrder") || values.containsKey("moveWidgets");
    }
    static Map<String, Object> apply(Map<String, Object> values, Map<?, ?> previous) {
        if (!FIELDS.containsAll(values.keySet()) || !(previous.get("widgets") instanceof List<?> originals)) throw invalid();
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("name", "period", "deviceRefs")) {
            if (previous.get(key) != null) result.put(key, previous.get(key));
            if (values.containsKey(key)) {
                if (values.get(key) == null) throw invalid();
                result.put(key, values.get(key));
            }
        }
        var widgets = new LinkedHashMap<String, Map<String, Object>>();
        for (Object item : originals) {
            if (!(item instanceof Map<?, ?> panel) || !(panel.get("key") instanceof String key)) throw invalid();
            widgets.put(key, copy(panel));
        }
        var touched = new java.util.HashSet<String>();
        for (Object item : list(values, "widgetEdits")) {
            if (!(item instanceof Map<?, ?> edit) || !(edit.get("key") instanceof String key) || !touched.add(key) || !widgets.containsKey(key)) throw invalid();
            if (Boolean.TRUE.equals(edit.get("remove"))) {
                if (!Set.of("key", "remove").containsAll(edit.keySet())) throw invalid();
                widgets.remove(key);
            } else {
                if (!DashboardCompositionCompiler.PANEL_FIELDS.containsAll(edit.keySet())) throw invalid();
                if (edit.get("dataRef") != null) {
                    for (String field : List.of("metric", "unit", "vehicleField", "sourceRef", "reducer", "deviceRefs")) widgets.get(key).remove(field);
                }
                if (edit.get("settings") instanceof Map<?, ?> settings && List.of("showTitle", "showMapDetails", "showMapNotices", "compact")
                        .stream().anyMatch(name -> edit.containsKey(name) && settings.containsKey(name))) throw invalid();
                for (var entry : edit.entrySet()) {
                    if ("settings".equals(entry.getKey())) {
                        if (!(entry.getValue() instanceof Map<?, ?> changes) || changes.isEmpty() || changes.size() > 32) throw invalid();
                        var merged = new LinkedHashMap<String, Object>();
                        if (widgets.get(key).get("settings") instanceof Map<?, ?> saved) merged.putAll(copySettings(saved));
                        for (var setting : changes.entrySet()) {
                            if (!(setting.getKey() instanceof String name)) throw invalid();
                            merged.put(name, setting.getValue());
                            if (List.of("showTitle", "showMapDetails", "showMapNotices", "compact").contains(name)) widgets.get(key).remove(name);
                        }
                        widgets.get(key).put("settings", merged);
                    } else if (entry.getValue() == null) {
                        if (!CLEARABLE.contains(entry.getKey())) throw invalid();
                        widgets.get(key).remove(entry.getKey());
                    } else {
                        widgets.get(key).put((String) entry.getKey(), entry.getValue());
                        if (List.of("showTitle", "showMapDetails", "showMapNotices", "compact").contains(entry.getKey())
                                && widgets.get(key).get("settings") instanceof Map<?, ?> saved) {
                            var merged = copySettings(saved);
                            merged.remove(entry.getKey());
                            widgets.get(key).put("settings", merged);
                        }
                    }
                }
            }
        }
        for (Object item : list(values, "addWidgets")) {
            if (!(item instanceof Map<?, ?> panel) || !(panel.get("key") instanceof String key)
                    || !DashboardCompositionCompiler.PANEL_FIELDS.containsAll(panel.keySet()) || widgets.containsKey(key) || touched.contains(key)) throw invalid();
            for (var existing : widgets.values()) if (sameData(panel, existing, previous)) {
                throw new DashboardPreparationRejected("This added widget duplicates existing key " + existing.get("key") + ". Reuse/edit the existing widget instead of adding the same field/metric chart twice.");
            }
            widgets.put(key, copy(panel));
        }
        List<Map<String, Object>> ordered = new ArrayList<>(widgets.values());
        if (values.containsKey("moveWidgets")) {
            if (values.containsKey("widgetOrder") || !(values.get("moveWidgets") instanceof Map<?, ?> move)
                    || !Set.of("keys", "position", "anchor").containsAll(move.keySet())
                    || !(move.get("keys") instanceof List<?> keys) || keys.isEmpty() || keys.size() > 12
                    || new java.util.HashSet<>(keys).size() != keys.size() || !widgets.keySet().containsAll(keys)) throw invalid();
            String position = String.valueOf(move.get("position"));
            if (!Set.of("START", "END", "BEFORE", "AFTER").contains(position)) throw invalid();
            var remaining = new ArrayList<>(ordered.stream().filter(panel -> !keys.contains(panel.get("key"))).toList());
            int index = position.equals("START") ? 0 : remaining.size();
            if (position.equals("BEFORE") || position.equals("AFTER")) {
                index = -1;
                for (int i = 0; i < remaining.size(); i++) if (java.util.Objects.equals(remaining.get(i).get("key"), move.get("anchor"))) index = i;
                if (index < 0) throw invalid();
                if (position.equals("AFTER")) index++;
            }
            remaining.addAll(index, keys.stream().map(widgets::get).toList());
            ordered = remaining;
        }
        if (values.containsKey("widgetOrder")) {
            List<?> order = list(values, "widgetOrder");
            if (order.size() != widgets.size() || new java.util.HashSet<>(order).size() != order.size() || !widgets.keySet().containsAll(order)) throw invalid();
            ordered = order.stream().map(widgets::get).toList();
        }
        if (ordered.isEmpty() || ordered.size() > 12) throw invalid();
        result.put("widgets", ordered);
        return result;
    }
    static String describe(Map<String, Object> edits, Map<?, ?> previous) {
        var notes = new ArrayList<String>();
        if (edits.containsKey("moveWidgets")) notes.add("Перемещены выбранные виджеты; порядок остальных сохранён.");
        if (edits.containsKey("widgetOrder")) notes.add("Изменён порядок виджетов.");
        for (Object item : list(edits, "widgetEdits")) {
            Map<?, ?> edit = (Map<?, ?>) item;
            Map<?, ?> prior = ((List<?>) previous.get("widgets")).stream().filter(value -> value instanceof Map<?, ?> panel && java.util.Objects.equals(panel.get("key"), edit.get("key")))
                    .map(value -> (Map<?, ?>) value).findFirst().orElse(Map.of());
            String title = String.valueOf(prior.get("title"));
            if (Boolean.TRUE.equals(edit.get("remove"))) notes.add("Убран виджет «" + title + "».");
            else {
                if (edit.containsKey("title")) notes.add("«" + title + "» переименован в «" + edit.get("title") + "».");
                if (edit.containsKey("height")) {
                    if (java.util.Objects.equals(prior.get("height"), edit.get("height")) && Integer.valueOf(1).equals(edit.get("height"))) notes.add("«" + title + "»: уже минимальная высота, 1 строка.");
                    else notes.add("Высота «" + title + "»: " + prior.get("height") + " → " + edit.get("height") + " строк.");
                }
                if (edit.containsKey("showMapNotices")) notes.add("«" + title + "»: " + (Boolean.TRUE.equals(edit.get("showMapNotices")) ? "подписи под картой показаны." : "подписи под картой скрыты."));
                if (edit.containsKey("settings")) notes.add("Изменены настройки отображения «" + title + "».");
                if (edit.containsKey("compact")) notes.add("«" + title + "»: " + (Boolean.TRUE.equals(edit.get("compact")) ? "компактный вид." : "полный вид."));
                if (edit.containsKey("width")) {
                    String width = String.valueOf(edit.get("width"));
                    notes.add("Ширина «" + title + "»: " + Map.of("FULL", "на всю строку", "HALF", "половина строки", "THIRD", "треть строки").getOrDefault(width, width) + ".");
                }
            }
        }
        if (edits.containsKey("addWidgets")) notes.add("Добавлены выбранные виджеты.");
        if (edits.containsKey("name")) notes.add("Название дашборда: «" + edits.get("name") + "».");
        if (edits.containsKey("period")) notes.add("Изменён период дашборда.");
        return notes.isEmpty() ? "Сохранены настройки предыдущего варианта." : String.join(" ", notes);
    }
    private static boolean sameData(Map<?, ?> first, Map<?, ?> second, Map<?, ?> root) {
        if (!java.util.Objects.equals(first.get("type"), second.get("type"))
                || !(first.containsKey("vehicleField") || first.containsKey("metric"))) return false;
        for (String field : List.of("vehicleField", "metric", "unit", "sourceRef", "deviceRefs", "period", "visualization", "reducer")) {
            Object a = first.get(field), b = second.get(field);
            Object fallback = switch (field) {
                case "period", "deviceRefs" -> root.get(field);
                case "reducer" -> "RAW";
                case "visualization" -> "vehicle-timeline".equals(first.get("type")) ? "list" : "time-series".equals(first.get("type")) ? "line" : "number";
                default -> null;
            };
            if (!java.util.Objects.equals(a == null ? fallback : a, b == null ? fallback : b)) return false;
        }
        return true;
    }
    private static List<?> list(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) return List.of();
        if (!(values.get(key) instanceof List<?> list) || list.size() > 12) throw invalid();
        return list;
    }
    private static Map<String, Object> copySettings(Map<?, ?> settings) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : settings.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid();
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static Map<String, Object> copy(Map<?, ?> panel) {
        var result = new LinkedHashMap<String, Object>();
        panel.forEach((key, value) -> { if (key instanceof String name && value != null) result.put(name, value); });
        return result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid incremental dashboard edit"); }
}
