package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Planner chooses scoped references; the server supplies resource identities and current source provenance. */
public final class PrimaryPlannerProjection {
    public static final String VERSION = "planner-references-v4";
    public static final String GUIDANCE = "Planner wire contract " + VERSION + ": resource bindings use "
            + "{kind:resource_refs,refs:[resource1,...]} with exact resourceChoices[].ref values. "
            + "Use multiple refs only for deviceIds. Never output literal resource UUIDs. fact_ref and step_output remain unchanged. "
            + "Each resource advertises bindingFields; an active dashboard review is the work subject, not a deviceId/sourceId binding. "
            + "New proposedGoals contain goalId and text only; the server attaches the current messageId. "
            + "Every step needs nonempty goalIds referencing retained goals or proposedGoals. "
            + "ASK.missingFields may contain only intent or target for routing ambiguity. "
            + "Names, widget settings and other domain parameters belong to the selected skill, not planner questions.";
    private final ObjectMapper mapper;
    private final JsonNode request;
    private final JsonNode schema;
    private final Map<String, JsonNode> resources = new LinkedHashMap<>();

    public PrimaryPlannerProjection(ObjectMapper mapper, JsonNode request, JsonNode canonicalSchema) {
        this.mapper = mapper;
        this.request = request.deepCopy();
        int index = 0;
        for (var choice : request.path("resourceChoices")) resources.put("resource" + (++index), choice.deepCopy());
        var projected = canonicalSchema.<ObjectNode>deepCopy();
        var bindings = (ArrayNode) projected.at("/$defs/binding/oneOf");
        // Canonical literal is free JSON; planner resource identities must instead come from the scoped snapshot.
        for (int i = bindings.size() - 1; i >= 0; i--) {
            if (bindings.get(i).at("/properties/kind/const").asText().equals("literal")) bindings.remove(i);
        }
        if (resources.values().stream().anyMatch(choice -> !bindingFields(choice).isEmpty())) {
            var reference = bindings.addObject().put("type", "object").put("additionalProperties", false);
            reference.putArray("required").add("kind").add("refs");
            var properties = reference.putObject("properties");
            properties.putObject("kind").put("const", "resource_refs");
            var refs = properties.putObject("refs").put("type", "array").put("minItems", 1).put("maxItems", 20).put("uniqueItems", true);
            var values = refs.putObject("items").put("type", "string").putArray("enum");
            resources.forEach((ref, choice) -> { if (!bindingFields(choice).isEmpty()) values.add(ref); });
        }
        restrictInputReferences(projected);
        var goal = (ObjectNode) projected.at("/$defs/goalProposal");
        ((ObjectNode) goal.path("properties")).remove("sourceMessageId");
        var required = (ArrayNode) goal.path("required");
        for (int i = required.size() - 1; i >= 0; i--) if (required.get(i).asText().equals("sourceMessageId")) required.remove(i);
        var missing = (ObjectNode) projected.at("/$defs/question/properties/missingFields/items");
        missing.putArray("enum").add("intent").add("target");
        if (request.at("/planSummary/steps").isArray()) {
            var decisions = ((ObjectNode) projected.at("/$defs/plannerResponse/properties/decision")).putArray("enum");
            if (request.at("/planSummary/steps").isEmpty()) decisions.add("PLAN");
            else decisions.add("AMEND").add("CONTINUE");
            decisions.add("ASK").add("UNSUPPORTED");
        }
        this.schema = projected;
    }

    public JsonNode schema() { return schema.deepCopy(); }

    public JsonNode request() {
        var result = request.<ObjectNode>deepCopy();
        long reviewChoices = resources.values().stream().filter(choice -> choice.path("kind").asText().equals("dashboard-review")).count();
        int index = 0;
        for (var choice : result.path("resourceChoices")) {
            String ref = "resource" + (++index);
            ((ObjectNode) choice).put("ref", ref);
            if (choice.path("properties") instanceof ObjectNode properties) {
                properties.set("bindingFields", mapper.valueToTree(bindingFields(choice)));
                boolean focused = request.at("/dialogue/focus/reviewId").isTextual()
                        && request.at("/dialogue/focus/reviewId").equals(properties.path("reviewId"));
                boolean explicitBase = !request.at("/dialogue/focus/reviewId").isTextual() && reviewChoices == 1
                        && properties.path("explicitBase").asBoolean();
                if (choice.path("kind").asText().equals("dashboard-review") && (focused || explicitBase)) {
                    properties.put("role", "ACTIVE_SUBJECT");
                    result.putObject("activeSubject").put("ref", ref).put("kind", "dashboard-review")
                            .set("label", choice.path("label").deepCopy());
                    result.withObject("/activeSubject").set("requiredIntent", properties.path("requiredIntent").deepCopy());
                }
                properties.remove(List.of("deviceId", "sourceId"));
            }
        }
        if (result.has("activeSubject")) for (var choice : result.path("resourceChoices")) {
            if (choice.path("kind").asText().equals("device") && choice.path("properties") instanceof ObjectNode properties)
                properties.put("role", "CONTEXT_RESOURCE");
        }
        return result;
    }

    private List<String> bindingFields(JsonNode choice) {
        return switch (choice.path("kind").asText()) {
            case "device" -> List.of("deviceId", "deviceIds");
            case "source" -> List.of("sourceId");
            default -> List.of();
        };
    }

    private void restrictInputReferences(ObjectNode projected) {
        var fields = new java.util.TreeSet<String>();
        for (var intent : request.path("catalogue")) for (var field : intent.path("inputKeys")) fields.add(field.asText());
        if (fields.isEmpty()) return;
        JsonNode original = projected.at("/$defs/input").deepCopy();
        var inputs = mapper.createObjectNode().putArray("oneOf");
        for (String field : fields) {
            var input = original.<ObjectNode>deepCopy();
            input.withObject("/properties").putObject("field").put("const", field);
            var variants = projected.at("/$defs/binding/oneOf").<ArrayNode>deepCopy();
            for (int i = variants.size() - 1; i >= 0; i--) {
                if (!variants.get(i).at("/properties/kind/const").asText().equals("resource_refs")) continue;
                var refs = (ObjectNode) variants.get(i).at("/properties/refs");
                var values = refs.withObject("/items").putArray("enum");
                resources.forEach((ref, choice) -> { if (value(choice, field) != null) values.add(ref); });
                if (values.isEmpty()) variants.remove(i);
                else if (!field.equals("deviceIds")) refs.put("maxItems", 1);
            }
            input.withObject("/properties").putObject("binding").set("oneOf", variants);
            inputs.add(input);
        }
        ((ObjectNode) projected.path("$defs")).set("input", mapper.createObjectNode().set("oneOf", inputs));
    }

    public JsonNode normalize(JsonNode wire, ProtocolValidator validator) {
        for (var step : wire.path("steps")) checkReferences(step.path("inputs"), wire);
        for (var choice : wire.at("/question/choices")) checkReferences(choice.path("bindings"), wire);
        try {
            validator.validateRegistered(schema, wire);
        } catch (ProtocolValidator.RegisteredViolation invalid) {
            throw new ModelProposalRejected(wire, VERSION + ": " + invalid.locations()
                    + ". Use resource_refs from the supplied choices, no literal UUIDs or sourceMessageId; planner questions are intent/target only.");
        }
        if (wire.path("decision").asText().equals("ASK") && request().has("activeSubject")
                && wire.at("/question/choices").isEmpty()) {
            for (var field : wire.at("/question/missingFields")) if (field.asText().equals("target"))
                throw new ModelProposalRejected(wire, "SELECTED_SUBJECT_REQUIRES_SKILL_DISPATCH: activeSubject already resolves the work item. "
                        + "Dispatch its requiredIntent for domain changes/questions; target ambiguity needs actual scoped choices, not a repeated domain-value question.");
        }
        var result = wire.deepCopy();
        for (var step : result.path("steps")) lowerInputs(step.path("inputs"), wire);
        for (var choice : result.at("/question/choices")) lowerInputs(choice.path("bindings"), wire);
        for (var goal : result.path("proposedGoals")) ((ObjectNode) goal).put("sourceMessageId", request.path("messageId").asText());
        return result;
    }

    private void checkReferences(JsonNode inputs, JsonNode wire) {
        for (var input : inputs) if (input.at("/binding/kind").asText().equals("resource_refs")) {
            for (var ref : input.at("/binding/refs")) if (resources.containsKey(ref.asText())
                    && value(resources.get(ref.asText()), input.path("field").asText()) == null)
                reject(wire, "RESOURCE_REFERENCE_FIELD_MISMATCH");
        }
    }

    /** A canonical proposal rejected by graph validation must be shown in the same wire language on correction. */
    public JsonNode correction(JsonNode rejected) {
        var result = rejected.deepCopy();
        for (var goal : result.path("proposedGoals")) if (goal instanceof ObjectNode object) object.remove("sourceMessageId");
        for (var step : result.path("steps")) raiseInputs(step.path("inputs"));
        for (var choice : result.at("/question/choices")) raiseInputs(choice.path("bindings"));
        return result;
    }

    private void lowerInputs(JsonNode inputs, JsonNode wire) {
        for (var input : inputs) {
            if (!input.path("binding").path("kind").asText().equals("resource_refs")) continue;
            String field = input.path("field").asText();
            var refs = input.at("/binding/refs");
            if (!field.equals("deviceIds") && refs.size() != 1) reject(wire, "RESOURCE_FIELD_REQUIRES_ONE_REFERENCE");
            var values = mapper.createArrayNode();
            for (var ref : refs) {
                JsonNode value = value(resources.get(ref.asText()), field);
                if (value == null) reject(wire, "RESOURCE_REFERENCE_FIELD_MISMATCH");
                values.add(value);
            }
            var binding = mapper.createObjectNode().put("kind", "literal");
            binding.set("value", field.equals("deviceIds") ? values : values.get(0));
            ((ObjectNode) input).set("binding", binding);
        }
    }

    private void raiseInputs(JsonNode inputs) {
        for (var input : inputs) {
            if (!input.at("/binding/kind").asText().equals("literal")) continue;
            String field = input.path("field").asText();
            var values = mapper.createArrayNode();
            if (input.at("/binding/value").isArray()) input.at("/binding/value").forEach(values::add);
            else values.add(input.at("/binding/value"));
            var binding = mapper.createObjectNode().put("kind", "resource_refs");
            var refs = binding.putArray("refs");
            for (var item : values) resources.forEach((ref, resource) -> {
                if (item.equals(value(resource, field))) refs.add(ref);
            });
            ((ObjectNode) input).set("binding", binding);
        }
    }

    private JsonNode value(JsonNode choice, String field) {
        if (choice == null) return null;
        String kind = choice.path("kind").asText();
        if (kind.equals("device") && List.of("deviceId", "deviceIds").contains(field)) return choice.path("properties").get("deviceId");
        if (kind.equals("source") && field.equals("sourceId")) return choice.path("properties").get("sourceId");
        return null;
    }

    private void reject(JsonNode proposal, String code) {
        throw new ModelProposalRejected(proposal, VERSION + ": " + code
                + ". Bind deviceId/deviceIds only to device choices and sourceId only to source choices. Leave unknown resources unbound for the selected skill.");
    }
}
