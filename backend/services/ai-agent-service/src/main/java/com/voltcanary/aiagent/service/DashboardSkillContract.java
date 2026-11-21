package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model-facing forms of the existing operation protocol; domain records/compiler stay unchanged. */
public final class DashboardSkillContract {
    public static final String CREATE = "CREATE_DASHBOARD";
    public static final String REFINE = "REFINE_DASHBOARD_REVIEW";
    public static final String UPDATE = "UPDATE_DASHBOARD_DRAFT";
    public static final String VERSION = "2";
    private static final Set<String> CREATION_OPERATIONS = Set.of("rename_dashboard", "show_map", "show_current", "show_history",
            "add_note", "place_widgets", "resize_widgets", "set_presentation", "rename_widget", "set_period");

    private DashboardSkillContract() { }

    public static boolean supports(String intent) { return Set.of(CREATE, REFINE, UPDATE).contains(intent); }

    public static JsonNode schema(ObjectMapper mapper, String intent) {
        return project(mapper, intent, mapper.valueToTree(DashboardOperationSchema.envelope()));
    }

    public static JsonNode schema(ObjectMapper mapper, String intent, Map<String, Object> context) {
        var schema = project(mapper, intent, mapper.valueToTree(DashboardOperationSchema.envelope(context)));
        if (context.get("requestedDashboardName") instanceof String name) {
            for (var variant : schema.at("/properties/operations/items/anyOf")) {
                if ("rename_dashboard".equals(variant.at("/properties/op/enum/0").asText())) {
                    ((ObjectNode) variant.at("/properties/name")).putArray("enum").add(name);
                }
            }
        }
        return schema;
    }

    private static JsonNode project(ObjectMapper mapper, String intent, JsonNode original) {
        if (!supports(intent)) throw new IllegalArgumentException("UNKNOWN_DASHBOARD_SKILL");
        var schema = original.<ObjectNode>deepCopy();
        if (UPDATE.equals(intent)) {
            var variants = mapper.createArrayNode();
            for (var variant : schema.at("/properties/operations/items/anyOf"))
                if (!"keep_review".equals(variant.at("/properties/op/enum/0").asText())) variants.add(variant);
            ((ObjectNode) schema.at("/properties/operations/items")).set("anyOf", variants);
            return schema;
        }
        if (!CREATE.equals(intent)) return schema;
        var variants = mapper.createArrayNode();
        for (var originalVariant : schema.at("/properties/operations/items/anyOf")) {
            var variant = originalVariant.<ObjectNode>deepCopy();
            String op = variant.at("/properties/op/enum/0").asText();
            if (!CREATION_OPERATIONS.contains(op)) continue;
            var properties = (ObjectNode) variant.path("properties");
            if (op.startsWith("show_")) {
                properties.remove("widgetKey");
                removeRequired(variant, "widgetKey");
            } else if (op.equals("rename_widget")) {
                properties.remove("widgetKey");
                removeRequired(variant, "widgetKey");
                properties.set("target", localReference(mapper));
                variant.withArray("required").add("target");
            }
            if (properties.has("targets")) properties.putObject("targets").put("type", "array").put("minItems", 1)
                    .put("maxItems", 12).set("items", localReference(mapper));
            // set_period uses an empty target list for the whole new dashboard.
            if (op.equals("set_period")) properties.putObject("targets").putArray("anyOf")
                    .add(mapper.createObjectNode().put("type", "null"))
                    .add(mapper.createObjectNode().put("type", "array").put("maxItems", 12).set("items", localReference(mapper)));
            if (properties.has("anchor") && !properties.path("anchor").path("type").asText().equals("null"))
                properties.set("anchor", localReference(mapper));
            variants.add(variant);
        }
        ((ObjectNode) schema.at("/properties/operations/items")).set("anyOf", variants);
        var operations = (ObjectNode) schema.at("/properties/operations");
        operations.put("description", "For PREPARED creation, start with exactly one rename_dashboard operation. "
                + "Its name is the exact supplied dashboard name or a short proposed name, up to 80 characters. "
                + "Include at least one widget and keep the resulting dashboard within 12 widgets. "
                + "If asking for a required name, return QUESTION without operations.");
        operations.putObject("contains").put("type", "object").putObject("properties").putObject("op").put("const", "rename_dashboard");
        operations.withObject("/contains").putArray("required").add("op");
        operations.put("minContains", 1).put("maxContains", 1);
        var widget = operations.putArray("allOf").addObject().putObject("contains").put("type", "object");
        widget.putArray("required").add("op");
        widget.putObject("properties").putObject("op").putArray("enum")
                .add("show_map").add("show_current").add("show_history").add("add_note");
        return schema;
    }

    private static ObjectNode localReference(ObjectMapper mapper) {
        return mapper.createObjectNode().put("type", "string").put("pattern", "^@[A-Za-z][A-Za-z0-9_-]{0,39}$");
    }

    private static void removeRequired(ObjectNode schema, String field) {
        var required = schema.withArray("required");
        for (int index = required.size() - 1; index >= 0; index--) if (required.get(index).asText().equals(field)) required.remove(index);
    }

    /** Validates references before the owning composition HTTP call. Unknown keys never become creations. */
    public static void validate(ObjectMapper mapper, ProtocolValidator validator, String intent, JsonNode payload, Map<String, Object> context) {
        WidgetDataCompatibility.validateReplacements(payload, context);
        var scopedSchema = schema(mapper, intent, context);
        validator.validateOperationDocument(scopedSchema, payload, scopedSchema, payload.path("operations"));
        var existing = new java.util.HashSet<String>();
        if (context.get("previousDashboardIntent") instanceof Map<?, ?> base && base.get("widgets") instanceof List<?> widgets)
            for (var widget : widgets) if (widget instanceof Map<?, ?> value && value.get("key") instanceof String key) existing.add(key);
        var created = new java.util.HashSet<String>();
        var operationIds = new java.util.HashSet<String>();
        for (var operation : payload.path("operations")) {
            String id = operation.path("id").asText();
            if (!operationIds.add(id)) throw new IllegalArgumentException("DUPLICATE_OPERATION_ID");
            String op = operation.path("op").asText();
            if (operation.hasNonNull("widgetKey")) requireTarget(operation.path("widgetKey").asText(), existing, created);
            if (operation.hasNonNull("target")) requireTarget(operation.path("target").asText(), existing, created);
            if (operation.hasNonNull("anchor")) requireTarget(operation.path("anchor").asText(), existing, created);
            for (var target : operation.path("targets")) {
                if (target.asText().equals("ALL") && op.equals("set_presentation") && operation.path("targets").size() == 1) continue;
                requireTarget(target.asText(), existing, created);
            }
            if (op.startsWith("show_") || op.equals("add_note")) created.add(id);
        }
    }

    private static void requireTarget(String key, Set<String> existing, Set<String> created) {
        if (key.startsWith("@")) {
            if (!created.contains(key.substring(1))) throw new IllegalArgumentException("UNKNOWN_OR_FORWARD_LOCAL_WIDGET_REF");
        } else if (!existing.contains(key)) throw new IllegalArgumentException("UNKNOWN_WIDGET_KEY");
    }

    /** Unique technical projection after validation; never infer intent from an unknown identifier. */
    public static Map<String, Object> domainPayload(ObjectMapper mapper, String intent, JsonNode payload) {
        var normalized = payload.<ObjectNode>deepCopy();
        if (CREATE.equals(intent)) for (var item : normalized.path("operations")) {
            var operation = (ObjectNode) item;
            if (operation.path("op").asText().startsWith("show_")) operation.putNull("widgetKey");
            if (operation.path("op").asText().equals("rename_widget")) operation.set("widgetKey", operation.remove("target"));
        }
        return mapper.convertValue(normalized, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    public static Set<String> loaderIds() { return Set.of("device.catalogue", "workflow.context", "conversation.history"); }
}
