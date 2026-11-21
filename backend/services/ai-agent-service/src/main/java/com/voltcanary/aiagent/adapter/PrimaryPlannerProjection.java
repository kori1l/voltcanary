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
    public static final String VERSION = "planner-references-v14";
    public static final String GUIDANCE = "Planner wire contract " + VERSION + ": resource bindings use "
            + "{kind:resource_refs,refs:[resource1,...]} with exact resourceChoices[].ref values. "
            + "Use multiple refs only for deviceIds. Never output literal resource UUIDs. fact_ref and step_output remain unchanged. "
            + "Each resource advertises bindingFields; an active dashboard review is the work subject, not a deviceId/sourceId binding. "
            + "For each step use only inputKeys from that exact intent's catalogue entry. Use inputs:[] when no routing binding is needed. "
            + "New proposedGoals contain goalId and text only; the server attaches the current messageId. "
            + "Every step needs nonempty goalIds referencing retained goals or proposedGoals. "
            + "For AMEND, when replacing a step, copy every still-pending goalId from that step onto a replacement or another retained step. "
            + "A newly proposed goal cannot cover an existing pending goal by itself; use the exact existing goalId. "
            + "pendingGoalCoverage lists the existing covering steps for each pending goal. When superseding all covering steps, preserve that goalId on a replacement unless the user explicitly withdraws it. "
            + "supersessionImpact lists downstream steps that would also be superseded. Account for their pending goals and never replace a step if that would invalidate a performed dependent. "
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
            var branch = (ObjectNode) bindings.get(i);
            switch (branch.at("/properties/kind/const").asText()) {
                case "literal" -> bindings.remove(i);
                case "fact_ref" -> branch.put("description", "Bind a fact already present in facts. Include only kind and the exact factId.");
                case "step_output" -> branch.put("description", "Bind a named output of a prerequisite step. Include only kind, stepId and outputKey. "
                        + "The server adds the producer to dependsOn from this binding. "
                        + "A dashboard review already supplied as activeSubject is not a step output.");
                default -> { }
            }
        }
        if (resources.values().stream().anyMatch(choice -> !bindingFields(choice).isEmpty())) {
            var reference = bindings.addObject().put("type", "object").put("additionalProperties", false);
            reference.put("description", "Bind an existing resource from resourceChoices. Include only kind and refs, "
                    + "where refs is an array of exact offered resource references. The input field belongs outside binding.");
            reference.putArray("required").add("kind").add("refs");
            var properties = reference.putObject("properties");
            properties.putObject("kind").put("const", "resource_refs");
            var refs = properties.putObject("refs").put("type", "array").put("minItems", 1).put("maxItems", 20).put("uniqueItems", true);
            var values = refs.putObject("items").put("type", "string").putArray("enum");
            resources.forEach((ref, choice) -> { if (!bindingFields(choice).isEmpty()) values.add(ref); });
        }
        restrictInputReferences(projected);
        var dependencies = (ObjectNode) projected.at("/$defs/stepProposal/properties/dependsOn");
        dependencies.put("description", "List additional prerequisites. The server includes producers named in step_output bindings. "
                + "Use a step ID from this response or an existing step that survives this amendment. "
                + "An accepted dashboard review is loaded from the active subject; it does not require a dependency on its replaced step.");
        projected.withObject("/$defs/stepProposal/properties/goalIds").put("description",
                "List the goals this step covers. During AMEND, consult pendingGoalCoverage and supersessionImpact. "
                        + "If all covering steps are replaced, carry each still-pending goal ID onto a replacement, "
                        + "alongside any new goal IDs. A new goal does not replace a retained ID.");
        restrictStepInputs(projected);
        var goal = (ObjectNode) projected.at("/$defs/goalProposal");
        var goalId = goal.withObject("/properties/goalId");
        goalId.put("description",
                "A fresh ID for a goal from the current request. Do not reuse any ID in existingGoalIds. "
                        + "Reference retained goal IDs in steps[].goalIds without proposing them again.");
        var occupiedGoalIds = mapper.createArrayNode();
        request.at("/planSummary/goals").forEach(existing -> {
            if (existing.path("goalId").isTextual()) occupiedGoalIds.add(existing.path("goalId"));
        });
        if (!occupiedGoalIds.isEmpty()) goalId.putObject("not").set("enum", occupiedGoalIds);
        ((ObjectNode) goal.path("properties")).remove("sourceMessageId");
        var required = (ArrayNode) goal.path("required");
        for (int i = required.size() - 1; i >= 0; i--) if (required.get(i).asText().equals("sourceMessageId")) required.remove(i);
        var missing = (ObjectNode) projected.at("/$defs/question/properties/missingFields/items");
        missing.putArray("enum").add("intent").add("target");
        if (request().has("activeSubject")) {
            missing.put("description", "The activeSubject already identifies the dashboard. Dispatch its requiredIntent "
                    + "for changes to that dashboard. Ask about a target only if the user refers to another unresolved resource.");
        } else if (selectedDeviceRef() != null) {
            missing.put("description", "target is only for a different unresolved resource. "
                    + "The selected device is already resolved; dispatch the skill if it is the requested target.");
        }
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
        var existingPrerequisites = mapper.createArrayNode();
        for (var step : request.at("/planSummary/steps")) {
            if (!java.util.Set.of("SUPERSEDED", "CANCELLED", "FAILED").contains(step.path("status").asText())
                    && step.path("stepId").isTextual()) existingPrerequisites.add(step.path("stepId").asText());
        }
        if (!existingPrerequisites.isEmpty()) result.set("existingPrerequisiteStepIds", existingPrerequisites);
        String selectedDevice = selectedDeviceRef();
        if (selectedDevice != null) result.put("selectedDeviceRef", selectedDevice);
        var plan = request.path("planSummary");
        var existingGoalIds = mapper.createArrayNode();
        var coverage = mapper.createArrayNode();
        for (var goal : plan.path("goals")) {
            if (goal.path("goalId").isTextual()) existingGoalIds.add(goal.path("goalId").asText());
            if (!"PENDING".equals(goal.path("disposition").asText())) continue;
            var id = goal.path("goalId");
            var entry = coverage.addObject();
            entry.set("goalId", id.deepCopy());
            var covering = entry.putArray("coveringStepIds");
            for (var step : plan.path("steps")) {
                if (java.util.Set.of("SUPERSEDED", "CANCELLED").contains(step.path("status").asText())) continue;
                for (var stepGoal : step.path("goalIds")) {
                    if (id.equals(stepGoal)) {
                        covering.add(step.path("stepId").asText());
                        break;
                    }
                }
            }
        }
        if (!existingGoalIds.isEmpty()) result.set("existingGoalIds", existingGoalIds);
        if (!coverage.isEmpty()) result.set("pendingGoalCoverage", coverage);
        var dependencies = new LinkedHashMap<String, List<String>>();
        for (var step : plan.path("steps")) {
            var parents = new java.util.ArrayList<String>();
            step.path("dependsOn").forEach(dependency -> parents.add(dependency.asText()));
            dependencies.put(step.path("stepId").asText(), parents);
        }
        var impacts = mapper.createObjectNode();
        for (var id : dependencies.keySet()) {
            var affected = com.voltcanary.aiagent.orchestration.plan.IntentPlanGraph
                    .supersessionClosure(java.util.Set.of(id), dependencies);
            affected.remove(id);
            if (!affected.isEmpty()) impacts.set(id, mapper.valueToTree(affected));
        }
        if (!impacts.isEmpty()) result.set("supersessionImpact", impacts);
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

    private void restrictStepInputs(ObjectNode projected) {
        var groups = new LinkedHashMap<List<String>, List<String>>();
        for (var intent : request.path("catalogue")) {
            var fields = new java.util.TreeSet<String>();
            intent.path("inputKeys").forEach(field -> fields.add(field.asText()));
            groups.computeIfAbsent(List.copyOf(fields), ignored -> new java.util.ArrayList<>())
                    .add(intent.path("intentId").asText());
        }
        if (groups.isEmpty()) return;
        var definitions = (ObjectNode) projected.path("$defs");
        var original = (ObjectNode) definitions.path("stepProposal");
        var alternatives = mapper.createArrayNode();
        groups.forEach((fields, intents) -> {
            var step = original.deepCopy();
            var properties = (ObjectNode) step.path("properties");
            var ids = properties.withObject("/intentId").putArray("enum");
            intents.forEach(ids::add);
            var inputs = properties.withObject("/inputs");
            if (fields.isEmpty()) {
                inputs.put("maxItems", 0);
            } else {
                var allowed = inputs.putObject("items").putArray("oneOf");
                for (var input : definitions.at("/input/oneOf")) {
                    if (fields.contains(input.at("/properties/field/const").asText())) allowed.add(input.deepCopy());
                }
            }
            alternatives.add(step);
        });
        var steps = (ObjectNode) definitions.at("/plannerResponse/properties/steps");
        steps.putObject("items").set("oneOf", alternatives);
    }

    public JsonNode normalize(JsonNode wire, ProtocolValidator validator) {
        for (var step : wire.path("steps")) checkReferences(step.path("inputs"), wire);
        for (var choice : wire.at("/question/choices")) checkReferences(choice.path("bindings"), wire);
        for (var step : wire.path("steps")) {
            var intent = step.path("intentId");
            var entry = java.util.stream.StreamSupport.stream(request.path("catalogue").spliterator(), false)
                    .filter(item -> intent.equals(item.path("intentId"))).findFirst().orElse(null);
            if (entry == null) continue;
            for (var input : step.path("inputs")) {
                var field = input.path("field");
                boolean allowed = java.util.stream.StreamSupport.stream(entry.path("inputKeys").spliterator(), false)
                        .anyMatch(field::equals);
                if (!allowed) throw new ModelProposalRejected(wire, "PLANNER_PARAMETER_NOT_ALLOWED: "
                        + "use only the selected intent's catalogue inputKeys " + entry.path("inputKeys")
                        + ". Omit unrelated bindings.");
            }
        }
        var usedGoalIds = new java.util.HashSet<String>();
        request.at("/planSummary/goals").forEach(goal -> usedGoalIds.add(goal.path("goalId").asText()));
        for (var goal : wire.path("proposedGoals")) {
            if (goal.path("goalId").isTextual() && !usedGoalIds.add(goal.path("goalId").asText())) {
                throw new ModelProposalRejected(wire, "PLANNER_GOAL_ID_ALREADY_USED: assign a fresh goalId to each new goal. "
                        + "Use existing goal IDs only in steps[].goalIds or supersedesGoalIds.");
            }
        }
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
        if (wire.path("decision").asText().equals("ASK") && selectedDeviceIsOnlyTarget(wire)) {
            throw new ModelProposalRejected(wire, "SELECTED_DEVICE_REQUIRES_SKILL_DISPATCH: the only target offered is the already selected device. "
                    + "Dispatch the requested workflow with that binding and let the step ask about its parameters.");
        }
        var result = wire.deepCopy();
        for (var goal : result.path("proposedGoals")) {
            ((ObjectNode) goal).put("sourceMessageId", request.path("messageId").asText());
        }
        for (var step : result.path("steps")) {
            var dependencies = ((ObjectNode) step).withArray("dependsOn");
            var declared = new java.util.HashSet<String>();
            dependencies.forEach(dependency -> declared.add(dependency.asText()));
            for (var input : step.path("inputs")) {
                var binding = input.path("binding");
                if ("step_output".equals(binding.path("kind").asText())) {
                    String producer = binding.path("stepId").asText();
                    if (declared.add(producer)) dependencies.add(producer);
                }
            }
            lowerInputs(step.path("inputs"), wire);
        }
        for (var choice : result.at("/question/choices")) lowerInputs(choice.path("bindings"), wire);
        return result;
    }

    private boolean selectedDeviceIsOnlyTarget(JsonNode wire) {
        var question = wire.path("question");
        if (question.path("missingFields").size() != 1 || !"target".equals(question.at("/missingFields/0").asText())) return false;
        var choices = question.path("choices");
        String device = selectedDeviceRef();
        if (device == null) return false;
        if (choices.isEmpty()) return true;
        if (choices.size() != 1 || choices.at("/0/bindings").size() != 1) return false;
        var binding = choices.at("/0/bindings/0");
        if (!binding.path("field").asText().equals("deviceId") && !binding.path("field").asText().equals("deviceIds")) return false;
        var ref = binding.at("/binding/refs/0").asText();
        return "resource_refs".equals(binding.at("/binding/kind").asText()) && binding.at("/binding/refs").size() == 1
                && device.equals(ref);
    }

    private String selectedDeviceRef() {
        var devices = resources.entrySet().stream()
                .filter(entry -> "device".equals(entry.getValue().path("kind").asText())).toList();
        return devices.size() == 1 && "UI_CONTEXT".equals(devices.getFirst().getValue().at("/properties/selection").asText())
                ? devices.getFirst().getKey() : null;
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
