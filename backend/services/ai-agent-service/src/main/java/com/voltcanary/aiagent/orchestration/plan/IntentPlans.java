package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Fact;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Goal;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PendingQuestion;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlanState;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepState;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-owned persisted state. Model proposals cannot construct reviews, receipts or confirmed facts. */
public final class IntentPlans {

    private IntentPlans() {}

    public static final class Rejected extends RuntimeException {

        private final int status;

        public Rejected(int status, String code) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public record Skill(String skillId, String version, String digest) {}

    public record Review(
        UUID reviewId,
        int version,
        String digest,
        List<UUID> actions,
        boolean requiresConfirmation
    ) {
        public Review {
            actions = List.copyOf(actions);
        }
    }

    /** A typed reference into an existing action receipt, not a second copy of domain execution. */
    public record Output(
        String key,
        String type,
        UUID reviewId,
        UUID actionId,
        String pointer,
        String resourceKind
    ) {}

    public record Confirmed(Fact fact, String type, Output output) {}

    /** Primitive-only library checkpoint, committed atomically with the authoritative plan. */
    public record GraphExecution(
        String engine,
        String definition,
        String requestId,
        String checkpointId,
        String node,
        String next,
        Map<String, Object> values
    ) {
        public GraphExecution {
            values = Map.copyOf(values);
            if (
                values.size() > 1 ||
                values
                    .entrySet()
                    .stream()
                    .anyMatch(
                        entry ->
                            !entry.getKey().equals("route") ||
                            !(entry.getValue() instanceof String text) ||
                            text.length() > 80
                    )
            ) {
                throw reject("INVALID_GRAPH_CHECKPOINT_VALUES");
            }
        }
    }

    public record State(
        UUID planId,
        UUID conversationId,
        long revision,
        String status,
        List<StepState> steps,
        List<Goal> goals,
        List<Confirmed> facts,
        PendingQuestion pendingQuestion,
        Focus focus,
        Map<String, Skill> skills,
        Map<String, Review> reviews,
        Map<String, List<Output>> outputs,
        String policyId,
        String policyVersion,
        Instant expiresAt,
        List<AssistantProtocol.LoaderRequest> contextRequests,
        GraphExecution execution
    ) {
        public State(
            UUID planId,
            UUID conversationId,
            long revision,
            String status,
            List<StepState> steps,
            List<Goal> goals,
            List<Confirmed> facts,
            PendingQuestion pendingQuestion,
            Focus focus,
            Map<String, Skill> skills,
            Map<String, Review> reviews,
            Map<String, List<Output>> outputs,
            String policyId,
            String policyVersion,
            Instant expiresAt,
            List<AssistantProtocol.LoaderRequest> contextRequests
        ) {
            this(
                planId,
                conversationId,
                revision,
                status,
                steps,
                goals,
                facts,
                pendingQuestion,
                focus,
                skills,
                reviews,
                outputs,
                policyId,
                policyVersion,
                expiresAt,
                contextRequests,
                null
            );
        }

        public State {
            steps = List.copyOf(steps);
            goals = List.copyOf(goals);
            facts = List.copyOf(facts);
            skills = Map.copyOf(skills);
            reviews = Map.copyOf(reviews);
            contextRequests = List.copyOf(contextRequests);
            outputs = outputs
                .entrySet()
                .stream()
                .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e ->
                        List.copyOf(e.getValue())
                    )
                );
        }

        public PlanState wire() {
            if (steps.isEmpty()) return null; // Initial ASK/UNSUPPORTED belongs to the durable turn, not a fictitious step.
            return new PlanState(
                "assistant-v2",
                "plan.state",
                planId.toString(),
                conversationId.toString(),
                revision,
                status,
                steps,
                facts.stream().map(Confirmed::fact).toList(),
                goals,
                policyId,
                policyVersion,
                planId.toString(),
                expiresAt.toString(),
                pendingQuestion,
                focus
            );
        }

        public State revise(
            long next,
            String nextStatus,
            List<StepState> nextSteps,
            List<Goal> nextGoals,
            List<Confirmed> nextFacts,
            PendingQuestion question,
            Focus nextFocus,
            Map<String, Skill> nextSkills,
            Map<String, Review> nextReviews,
            Map<String, List<Output>> nextOutputs
        ) {
            return new State(
                planId,
                conversationId,
                next,
                nextStatus,
                nextSteps,
                nextGoals,
                nextFacts,
                question,
                nextFocus,
                nextSkills,
                nextReviews,
                nextOutputs,
                policyId,
                policyVersion,
                expiresAt,
                contextRequests,
                execution
            );
        }

        public State revision(long next) {
            return revise(
                next,
                status,
                steps,
                goals,
                facts,
                pendingQuestion,
                focus,
                skills,
                reviews,
                outputs
            );
        }

        public State contextRequests(List<AssistantProtocol.LoaderRequest> requests) {
            return new State(
                planId,
                conversationId,
                revision,
                status,
                steps,
                goals,
                facts,
                pendingQuestion,
                focus,
                skills,
                reviews,
                outputs,
                policyId,
                policyVersion,
                expiresAt,
                requests,
                execution
            );
        }

        public State execution(GraphExecution position) {
            return new State(
                planId,
                conversationId,
                revision,
                status,
                steps,
                goals,
                facts,
                pendingQuestion,
                focus,
                skills,
                reviews,
                outputs,
                policyId,
                policyVersion,
                expiresAt,
                contextRequests,
                position
            );
        }

        public StepState step(String id) {
            return steps
                .stream()
                .filter(step -> step.stepId().equals(id))
                .findFirst()
                .orElseThrow(() -> reject("UNKNOWN_STEP"));
        }
    }

    public record Token(
        Scope owner,
        UUID conversationId,
        UUID planId,
        UUID requestId,
        UUID messageId,
        long revision,
        UUID primaryCallId,
        UUID replanCallId,
        String message,
        PendingQuestion answeredQuestion
    ) {}

    public record Claim(Token token, State state, boolean claimed, String requestStatus) {}

    public record ResourceEvent(
        long sequence,
        UUID sourceMessageId,
        String stepId,
        String intentId,
        String factId,
        Output output
    ) {}

    public record SourceMessage(UUID messageId, String text) {}

    public record Receipt(Review review, String status, Map<UUID, ActionReceipt> actions) {}

    public record ActionReceipt(String status, JsonNode result) {}

    public record ModelInput(
        Token token,
        State state,
        Map<String, JsonNode> resolvedInputs,
        SourceMessage currentMessage
    ) {}

    public static StepState step(
        StepState old,
        String status,
        String reviewId,
        String receiptId,
        AssistantProtocol.Diagnostic diagnostic
    ) {
        return new StepState(
            old.stepId(),
            old.intentId(),
            old.skillVersion(),
            old.schemaVersion(),
            old.revision() + 1,
            old.dependsOn(),
            old.inputs(),
            old.goalIds(),
            status,
            reviewId,
            receiptId,
            diagnostic
        );
    }

    public static State replace(
        State state,
        StepState replacement,
        String status,
        PendingQuestion question
    ) {
        return state.revise(
            state.revision(),
            status,
            state
                .steps()
                .stream()
                .map(step -> step.stepId().equals(replacement.stepId()) ? replacement : step)
                .toList(),
            state.goals(),
            state.facts(),
            question,
            state.focus(),
            state.skills(),
            state.reviews(),
            state.outputs()
        );
    }

    public static Rejected reject(String code) {
        return new Rejected(422, code);
    }

    public static Rejected conflict(String code) {
        return new Rejected(409, code);
    }
}
