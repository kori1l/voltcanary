package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DefinitionValidation {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "password", "token", "accesstoken", "refreshtoken", "apikey", "secret",
            "credentials", "authorization", "headers", "recipients", "script", "code",
            "sql", "endpoint", "httprequest", "iframe", "html", "rawvalues", "readings");
    private static final Pattern EXECUTABLE = Pattern.compile(
            "(?is)(<\\s*/?\\s*[a-z]+(?:\\s|>)|javascript\\s*:|data\\s*:|https?\\s*:\\s*//|Bearer\\s+\\S+|-----BEGIN .*PRIVATE KEY-----)");
    private final ObjectMapper mapper;
    private final ScreenGridValidation grids = new ScreenGridValidation();

    public DefinitionValidation(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DashboardDefinition validate(DashboardDefinition definition) {
        require(definition != null, "definition", "Definition is required");
        require(definition.schemaVersion() == 1, "schemaVersion", "Supported definition schema is 1");
        require(definition.name() != null && !definition.name().isBlank()
                && definition.name().length() <= 80, "name", "Name must contain 1–80 characters");
        require(definition.description() == null || definition.description().length() <= 500,
                "description", "Description exceeds 500 characters");
        require(definition.widgets() != null && definition.widgets().size() <= 24,
                "widgets", "At most 24 widgets are allowed");
        require(definition.sections() != null && definition.sections().size() <= 24,
                "sections", "At most 24 sections are allowed");
        require(definition.defaultContext() != null && definition.defaultContext().isObject(),
                "defaultContext", "Default context must be an object");
        Set<String> ids = new HashSet<>();
        for (DashboardDefinition.Widget widget : definition.widgets()) {
            require(widget != null && widget.id() != null && widget.id().matches("[A-Za-z0-9_-]{1,80}")
                    && ids.add(widget.id()), "widgets.id", "Widget IDs must be unique safe identifiers");
            require(widget.type() != null && widget.type().matches("[a-z][a-z0-9-]{0,79}")
                    && widget.typeVersion() > 0, "widgets.type", "Widget type and positive version are required");
            require(widget.title() != null && widget.title().length() <= 120,
                    "widgets.title", "Widget title exceeds 120 characters");
            require(widget.binding() == null || widget.binding().isNull() || widget.binding().isObject(),
                    "widgets.binding", "Binding must be an object");
            require(widget.query() == null || widget.query().isNull() || widget.query().isObject(),
                    "widgets.query", "Query must contain typed intent fields, not executable text");
        }
        JsonNode tree = mapper.valueToTree(definition);
        require(tree.toString().getBytes(StandardCharsets.UTF_8).length <= 131072,
                "definition", "Definition exceeds 128 KiB");
        inspect(tree, "definition", 0);
        validateScreens(definition, ids);
        validateLayouts(definition, ids);
        return definition;
    }

    private void validateScreens(DashboardDefinition definition, Set<String> widgetIds) {
        require(definition.screens().size() <= 8, "screens", "At most 8 screens are allowed");
        if (definition.screens().isEmpty()) return;
        Set<String> screenIds = new HashSet<>();
        Set<String> assigned = new HashSet<>();
        for (DashboardDefinition.Screen screen : definition.screens()) {
            require(screen != null && screen.id() != null && screen.id().matches("[A-Za-z0-9_-]{1,80}")
                    && screenIds.add(screen.id()), "screens.id", "Screen IDs must be unique safe identifiers");
            require(screen.title() != null && !screen.title().isBlank() && screen.title().length() <= 80,
                    "screens.title", "Screen title must contain 1–80 characters");
            require(screen.widgetIds() != null && screen.widgetIds().size() <= 24,
                    "screens.widgetIds", "Screen must list at most 24 existing widgets");
            grids.validate(screen.grid());
            for (String id : screen.widgetIds()) require(widgetIds.contains(id) && assigned.add(id),
                    "screens.widgetIds", "Each widget must belong to exactly one screen");
        }
        require(assigned.equals(widgetIds), "screens.widgetIds", "Every widget must belong to a screen");
        for (JsonNode section : definition.sections()) if (section.hasNonNull("screenId")) {
            String screenId = section.path("screenId").asText();
            require(screenIds.contains(screenId), "sections.screenId", "Section screen must exist");
            var screen = definition.screens().stream().filter(item -> item.id().equals(screenId)).findFirst().orElseThrow();
            section.path("widgetIds").forEach(id -> require(screen.widgetIds().contains(id.asText()),
                    "sections.widgetIds", "Section widgets must belong to its screen"));
        }
    }

    public void validatePayload(JsonNode payload) {
        require(payload != null && payload.isObject(), "request", "Request must be an object");
        require(payload.toString().getBytes(StandardCharsets.UTF_8).length <= 131072,
                "request", "Request exceeds 128 KiB");
        inspect(payload, "request", 0);
    }

    private void inspect(JsonNode node, String path, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        require(depth <= 12, path, "Payload nesting exceeds 12 levels");
        if (node.isObject()) {
            require(node.size() <= 64, path, "Object has too many fields");
            node.fields().forEachRemaining(entry -> {
                String normalized = entry.getKey().toLowerCase().replaceAll("[^a-z0-9]", "");
                require(!FORBIDDEN_KEYS.contains(normalized)
                        && !normalized.matches(".*(password|secret|credential|token).*"),
                        path + "." + entry.getKey(), "Unsafe field is forbidden");
                if (normalized.equals("url") || normalized.equals("href")) {
                    require(entry.getValue().isTextual()
                            && entry.getValue().asText().matches("/(devices|alerts|maintenance|settings)(/[A-Za-z0-9_-]+)*"),
                            path + "." + entry.getKey(), "Only safe internal navigation paths are allowed");
                }
                if (normalized.equals("deviceid") || normalized.equals("locationid")) {
                    uuid(entry.getValue(), path + "." + entry.getKey());
                }
                if (normalized.equals("deviceids") || normalized.equals("locationids")) {
                    require(entry.getValue().isArray() && entry.getValue().size() <= 500,
                            path + "." + entry.getKey(), "Resource selection exceeds 500 IDs");
                    entry.getValue().forEach(id -> uuid(id, path + "." + entry.getKey()));
                }
                inspect(entry.getValue(), path + "." + entry.getKey(), depth + 1);
            });
        } else if (node.isArray()) {
            require(node.size() <= 500, path, "Array exceeds 500 entries");
            for (int index = 0; index < node.size(); index++) {
                inspect(node.get(index), path + "[" + index + "]", depth + 1);
            }
        } else if (node.isTextual()) {
            require(node.asText().length() <= 4000 && !EXECUTABLE.matcher(node.asText()).find(),
                    path, "Unsafe or oversized text is forbidden");
        } else if (node.isNumber()) {
            require(Double.isFinite(node.asDouble()), path, "Numeric values must be finite");
        }
    }

    private void validateLayouts(DashboardDefinition definition, Set<String> widgetIds) {
        JsonNode layouts = definition.layouts();
        require(layouts != null && layouts.isObject(), "layouts", "Layouts must be an object");
        layouts.fields().forEachRemaining(entry -> {
            require(Set.of("desktop", "tablet", "mobile").contains(entry.getKey()),
                    "layouts", "Unknown layout breakpoint");
            JsonNode positions = entry.getValue();
            require(positions.isArray() && positions.size() <= 24, "layouts", "Layout must contain at most 24 positions");
            Set<String> placed = new HashSet<>();
            for (JsonNode position : positions) {
                require(position.isObject() && widgetIds.contains(position.path("widgetId").asText())
                        && placed.add(position.path("widgetId").asText()), "layouts", "Layout must reference unique existing widgets");
                for (String coordinate : Set.of("x", "y", "w", "h")) {
                    require(position.path(coordinate).isIntegralNumber() && position.path(coordinate).canConvertToInt(),
                            "layouts." + coordinate, "Layout coordinates must be bounded integers");
                }
                int columns = grids.columns(definition, position.path("widgetId").asText(), entry.getKey());
                int x = position.path("x").asInt();
                int y = position.path("y").asInt();
                int w = position.path("w").asInt();
                int h = position.path("h").asInt();
                require(x >= 0 && y >= 0 && y <= 10000 && w > 0 && w <= columns
                        && h > 0 && h <= 100 && x <= columns - w, "layouts", "Layout position exceeds bounds");
            }
        });
    }

    private void uuid(JsonNode value, String field) {
        try {
            require(value.isTextual() && value.asText().length() == 36, field, "Expected a resource UUID");
            UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw new DashboardFailure("VALIDATION_ERROR", "Expected a resource UUID", field);
        }
    }

    private void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new DashboardFailure("VALIDATION_ERROR", message, field);
        }
    }
}
