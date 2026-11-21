package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingFactRef;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingStepOutput;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Goal;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Manifest;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlannerResponse;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepState;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.conflict;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.reject;

/** Typed graph validation only. No user-language interpretation or external I/O. */
@RequiredArgsConstructor
public final class IntentPlanGraph {
    private final SkillRegistry registry;
    private final ProtocolValidator validator;
    private final ObjectMapper mapper;

    public IntentPlans.State apply(IntentPlans.State state, PlannerResponse proposal, Set<String> sourceMessages,
            String currentMessageId, boolean newUserTurn, Set<String> authorized, Set<String> capabilities) {
        registry.validatePlanner(mapper.valueToTree(proposal), authorized, capabilities);
        if (!Set.of("PLAN", "AMEND").contains(proposal.decision())) throw reject("GRAPH_DECISION_REQUIRED");
        if (proposal.decision().equals("PLAN") && !state.steps().isEmpty()) throw conflict("EXISTING_PLAN_REQUIRES_AMEND");
        if (proposal.decision().equals("AMEND") && !newUserTurn) throw reject("REPAIR_CANNOT_SUPERSEDE");
        var steps = new ArrayList<>(state.steps());
        var reviews = new HashMap<>(state.reviews());
        var skills = new HashMap<>(state.skills());
        var goals = new ArrayList<>(state.goals());
        var goalIds = new HashSet<String>();
        for (var goal : goals) goalIds.add(goal.goalId());
        for (var goal : proposal.proposedGoals()) {
            if (!currentMessageId.equals(goal.sourceMessageId()) || !sourceMessages.contains(goal.sourceMessageId())
                    || !goalIds.add(goal.goalId())) throw reject("GOAL_PROVENANCE_OR_ID");
            goals.add(new Goal(goal.goalId(), goal.sourceMessageId(), goal.text(), "PENDING", null));
        }
        var supersededGoals = new HashSet<>(proposal.supersedesGoalIds());
        if (supersededGoals.size() != proposal.supersedesGoalIds().size() || !goalIds.containsAll(supersededGoals)) throw reject("UNKNOWN_SUPERSEDED_GOAL");
        goals.replaceAll(goal -> {
            if (!supersededGoals.contains(goal.goalId())) return goal;
            if (!goal.disposition().equals("PENDING")) throw reject("COMPLETED_GOAL_IMMUTABLE");
            return new Goal(goal.goalId(), goal.sourceMessageId(), goal.text(), "SUPERSEDED", currentMessageId);
        });
        var superseded = new HashSet<>(proposal.supersedesStepIds());
        if (superseded.size() != proposal.supersedesStepIds().size()) throw reject("DUPLICATE_SUPERSESSION");
        for (String id : superseded) {
            var existing = state.step(id);
            if (Set.of("SUCCEEDED", "EXECUTING", "UNKNOWN").contains(existing.status())) throw reject("PERFORMED_STEP_IMMUTABLE");
        }
        // Every affected downstream pending review is invalidated, but actual receipts are never erased.
        var dependencies = new LinkedHashMap<String, List<String>>();
        steps.forEach(step -> dependencies.put(step.stepId(), step.dependsOn()));
        var affected = supersessionClosure(superseded, dependencies);
        for (var step : steps) {
            if (affected.contains(step.stepId()) && !superseded.contains(step.stepId())) {
                if (Set.of("SUCCEEDED", "EXECUTING", "UNKNOWN").contains(step.status())) throw reject("PERFORMED_DEPENDENT_IMMUTABLE");
            }
        }
        superseded.addAll(affected);
        steps.replaceAll(step -> {
            if (!superseded.contains(step.stepId())) return step;
            reviews.remove(step.stepId());
            return IntentPlans.step(step, "SUPERSEDED", null, step.receiptId(), null);
        });
        var ids = new HashSet<String>();
        for (var step : steps) ids.add(step.stepId());
        for (var step : proposal.steps()) {
            if (!ids.add(step.stepId())) throw reject("DUPLICATE_STEP_ID");
            Manifest manifest = registry.manifest(step.intentId(), authorized, capabilities);
            skills.put(step.stepId(), new IntentPlans.Skill(manifest.skillId(), manifest.skillVersion(), manifest.instructions().digest()));
            steps.add(new StepState(step.stepId(), step.intentId(), manifest.skillVersion(), "assistant-v2", 0L,
                    step.dependsOn(), step.inputs(), step.goalIds(), "BLOCKED", null, null, null));
        }
        var focus = state.focus();
        if (focus.stepId() != null && superseded.contains(focus.stepId())) {
            focus = new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus(focus.deviceRef(), focus.dashboardRef(), focus.reviewId(), null, "ACTIVE_PLAN");
        }
        var result = state.revise(state.revision(), "ACTIVE", steps, goals, state.facts(), null, focus, skills, reviews, state.outputs());
        validate(result, authorized, capabilities);
        result = result.revise(result.revision(), "ACTIVE", refresh(result.steps()), goals, result.facts(), null, result.focus(), skills, reviews, result.outputs());
        validateWire(result);
        return result;
    }

    public static Set<String> supersessionClosure(Set<String> roots, Map<String, List<String>> dependencies) {
        var affected = new java.util.LinkedHashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var step : dependencies.entrySet()) {
                if (step.getValue().stream().anyMatch(affected::contains)) changed |= affected.add(step.getKey());
            }
        } while (changed);
        return affected;
    }

    public void validate(IntentPlans.State state, Set<String> authorized, Set<String> capabilities) {
        if (state.steps().size() > 8 || state.goals().size() > 32) throw reject("PLAN_SIZE_LIMIT");
        var indexed = new LinkedHashMap<String, StepState>();
        var manifests = new HashMap<String, Manifest>();
        for (var step : state.steps()) {
            if (indexed.putIfAbsent(step.stepId(), step) != null) throw reject("DUPLICATE_STEP_ID");
            var skill = state.skills().get(step.stepId());
            if (skill == null) throw reject("SKILL_BINDING_MISSING");
            var manifest = registry.require(step.intentId(), skill.skillId(), skill.version(), authorized, capabilities);
            if (!skill.digest().equals(manifest.instructions().digest())) throw reject("SKILL_DIGEST_CHANGED");
            manifests.put(step.stepId(), manifest);
        }
        Set<String> liveGoals = state.goals().stream().filter(goal -> !goal.disposition().equals("SUPERSEDED")).map(Goal::goalId).collect(java.util.stream.Collectors.toSet());
        for (var step : state.steps()) {
            if (step.status().equals("SUPERSEDED")) continue;
            if (step.goalIds().isEmpty() || !liveGoals.containsAll(step.goalIds())) throw reject("STEP_GOAL_MISSING");
            if (new HashSet<>(step.dependsOn()).size() != step.dependsOn().size()) throw reject("DUPLICATE_DEPENDENCY");
            for (String dep : step.dependsOn()) if (!indexed.containsKey(dep) || indexed.get(dep).status().equals("SUPERSEDED")) throw reject("DEPENDENCY_UNAVAILABLE");
            var fields = new HashSet<String>();
            for (var input : step.inputs()) {
                if (!fields.add(input.field())) throw reject("DUPLICATE_INPUT");
                var rule = manifests.get(step.stepId()).parameterRules().stream().filter(item -> item.field().equals(input.field())).findFirst()
                        .orElseThrow(() -> reject("UNKNOWN_INPUT_FIELD"));
                if (input.binding() instanceof BindingStepOutput output) {
                    if (!indexed.containsKey(output.stepId()) || !reachable(step.stepId(), output.stepId(), indexed, new HashSet<>())) throw reject("OUTPUT_WITHOUT_DEPENDENCY");
                    var spec = manifests.get(output.stepId()).outputs().stream().filter(item -> item.key().equals(output.outputKey())).findFirst()
                            .orElseThrow(() -> reject("UNKNOWN_OUTPUT_KEY"));
                    if (!rule.type().equals(spec.type())) throw reject("BINDING_TYPE_MISMATCH");
                } else if (input.binding() instanceof BindingFactRef fact) {
                    var value = state.facts().stream().filter(item -> item.fact().factId().equals(fact.factId())).findFirst()
                            .orElseThrow(() -> reject("UNKNOWN_FACT"));
                    if (!rule.type().equals(value.type())) throw reject("BINDING_TYPE_MISMATCH");
                }
            }
        }
        var visited = new HashSet<String>();
        for (String id : indexed.keySet()) visit(id, indexed, visited, new HashSet<>());
        // Omitting a retained requirement is not implicit permission to delete it.
        Set<String> covered = state.steps().stream().filter(step -> !Set.of("SUPERSEDED", "CANCELLED").contains(step.status()))
                .flatMap(step -> step.goalIds().stream()).collect(java.util.stream.Collectors.toSet());
        for (var goal : state.goals()) if (goal.disposition().equals("PENDING") && !covered.contains(goal.goalId())) throw reject("UNCOVERED_RETAINED_GOAL");
    }

    public static List<StepState> refresh(List<StepState> steps) {
        return steps.stream().map(step -> {
            if (!Set.of("BLOCKED", "READY").contains(step.status())) return step;
            boolean ready = step.dependsOn().stream().allMatch(id -> steps.stream().anyMatch(dep -> dep.stepId().equals(id) && dep.status().equals("SUCCEEDED")));
            String status = ready ? "READY" : "BLOCKED";
            return step.status().equals(status) ? step : IntentPlans.step(step, status, step.reviewId(), step.receiptId(), step.diagnostic());
        }).toList();
    }

    public void validateWire(IntentPlans.State state) {
        if (state.wire() != null) validator.validate("planState", mapper.valueToTree(state.wire()));
    }

    public Manifest skill(IntentPlans.State state, String stepId, Set<String> authorized, Set<String> capabilities) {
        var step = state.step(stepId);
        var binding = state.skills().get(stepId);
        return registry.require(step.intentId(), binding.skillId(), binding.version(), authorized, capabilities);
    }

    public void validateStepResponse(com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponse response) {
        validator.validate("stepResponse", mapper.valueToTree(response));
    }

    public void validateCommand(Manifest skill, com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Command command,
            Set<String> authorized, Set<String> capabilities) {
        registry.validateCommand(skill.intentId(), mapper.valueToTree(command), authorized, capabilities);
    }

    private static void visit(String id, Map<String, StepState> steps, Set<String> visited, Set<String> active) {
        if (visited.contains(id)) return;
        if (!active.add(id)) throw reject("DEPENDENCY_CYCLE");
        for (String dep : steps.get(id).dependsOn()) {
            if (!steps.containsKey(dep)) throw reject("UNKNOWN_DEPENDENCY");
            visit(dep, steps, visited, active);
        }
        active.remove(id);
        visited.add(id);
    }

    private static boolean reachable(String from, String to, Map<String, StepState> steps, Set<String> seen) {
        if (!seen.add(from)) return false;
        var source = steps.get(from);
        return source != null && (source.dependsOn().contains(to) || source.dependsOn().stream().anyMatch(id -> reachable(id, to, steps, seen)));
    }
}
