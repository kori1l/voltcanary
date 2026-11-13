package com.voltcanary.aiagent.orchestration.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/** Typed wire shapes of the canonical assistant-v2 schema. Use ProtocolValidator before binding. */
public final class AssistantProtocol {
    private AssistantProtocol() { }

    public record Fact(
            String factId,
            String field,
            JsonNode value,
            String origin,
            String sourceMessageId,
            String receiptId,
            String confirmation
    ) { }

    public record Choice(
            String ref,
            String kind,
            String label,
            JsonNode properties
    ) { }

    public record Goal(
            String goalId,
            String sourceMessageId,
            String text,
            String disposition,
            String supersededByMessageId
    ) { }

    public record BindingLiteral(
            String kind,
            JsonNode value
    ) implements Binding { }

    public record BindingFactRef(
            String kind,
            String factId
    ) implements Binding { }

    public record BindingStepOutput(
            String kind,
            String stepId,
            String outputKey
    ) implements Binding { }

    public record Input(
            String field,
            Binding binding
    ) { }

    public record StepProposal(
            String stepId,
            String intentId,
            List<String> dependsOn,
            List<Input> inputs,
            List<String> goalIds
    ) { }

    public record QuestionChoicesItem(
            String label,
            List<Input> bindings
    ) { }

    public record Question(
            String text,
            List<String> missingFields,
            List<QuestionChoicesItem> choices
    ) { }

    public record Diagnostic(
            String category,
            String code,
            String message,
            List<String> fieldPaths,
            Boolean retriable
    ) { }

    public record LoaderRequest(
            String loaderId,
            String argumentsSchemaId,
            JsonNode arguments,
            String cursor
    ) { }

    public record ContextSize(
            Long utf8Bytes,
            Long inputTokens,
            String estimate
    ) { }

    public record Context(
            String version,
            String kind,
            String snapshotId,
            String asOf,
            String projectionVersion,
            String completeness,
            List<String> omittedSections,
            List<Fact> facts,
            List<Choice> choices,
            List<Goal> goals,
            String baseReviewId,
            String baseDigest,
            List<LoaderRequest> contextRequests,
            ContextSize size,
            Dialogue dialogue
    ) { }

    public record IntentSummaryOutputsItem(
            String key,
            String type
    ) { }

    public record IntentSummary(
            String intentId,
            String description,
            List<String> inputKeys,
            List<IntentSummaryOutputsItem> outputs,
            List<String> requires
    ) { }

    public record PlanSummaryStepsItem(
            String stepId,
            String intentId,
            String status,
            String receiptId,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> dependsOn,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> goalIds
    ) {
        public PlanSummaryStepsItem(String stepId, String intentId, String status, String receiptId) {
            this(stepId, intentId, status, receiptId, null, null);
        }
    }

    public record PlanSummary(
            String planId,
            Long revision,
            List<PlanSummaryStepsItem> steps,
            List<Goal> goals
    ) { }

    public record RoutingPreference(
            String mode,
            String policyId,
            String modelConfigId
    ) { }

    public record TurnSubmitSelectionItem(
            String field,
            String ref
    ) { }

    public record TurnSubmit(
            String version,
            String kind,
            String requestId,
            Long expectedRevision,
            String message,
            String selectedReviewId,
            List<TurnSubmitSelectionItem> selection,
            RoutingPreference routing,
            String replyToQuestionId
    ) { }

    public record PlannerRequest(
            String version,
            String kind,
            String requestId,
            String messageId,
            String message,
            List<IntentSummary> catalogue,
            PlanSummary planSummary,
            List<Fact> facts,
            List<Choice> resourceChoices,
            Dialogue dialogue
    ) { }

    public record PlannerResponse(
            String version,
            String kind,
            String decision,
            String explanation,
            String targetStepId,
            List<String> supersedesStepIds,
            Question question,
            List<StepProposal> steps,
            List<GoalProposal> proposedGoals,
            List<String> supersedesGoalIds
    ) { }

    public record ManifestParameterRulesItem(
            String field,
            String type,
            Boolean required,
            Boolean nullable,
            String description,
            Long maxLength,
            List<JsonNode> allowedValues,
            String proposalPolicy
    ) { }

    public record ManifestOutputsItem(
            String key,
            String type
    ) { }

    public record ManifestInstructions(
            String text,
            String digest
    ) { }

    public record Manifest(
            String version,
            String kind,
            String skillId,
            String skillVersion,
            String intentId,
            String description,
            List<String> protocolVersions,
            String inputSchemaId,
            String outputSchemaId,
            String commandSchemaId,
            List<ManifestParameterRulesItem> parameterRules,
            List<ManifestOutputsItem> outputs,
            String executionMode,
            String confirmation,
            List<String> loaderIds,
            List<String> toolIds,
            List<String> checkIds,
            String modelProfileId,
            Long maxContextBytes,
            ManifestInstructions instructions
    ) { }

    public record Command(
            String schemaId,
            String schemaVersion,
            JsonNode payload
    ) { }

    public record StepRequestSkill(
            String skillId,
            String skillVersion
    ) { }

    public record StepRequestInstructions(
            String text,
            String digest
    ) { }

    public record StepRequest(
            String version,
            String kind,
            String callId,
            String planId,
            String stepId,
            String intentId,
            StepRequestSkill skill,
            StepRequestInstructions instructions,
            Context context,
            String responseSchemaId,
            Diagnostic repair,
            String messageId,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode contract,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<LoadedContext> loadResults
    ) {
        public StepRequest(String version, String kind, String callId, String planId, String stepId, String intentId,
                StepRequestSkill skill, StepRequestInstructions instructions, Context context, String responseSchemaId,
                Diagnostic repair, String messageId, String message) {
            this(version, kind, callId, planId, stepId, intentId, skill, instructions, context, responseSchemaId, repair, messageId, message, null, null);
        }
    }

    public record LoadedContext(String loaderId, String snapshotId, String asOf, String completeness,
            String nextCursor, JsonNode data) { }

    public record StepResponsePrepared(
            String version,
            String kind,
            String outcome,
            String explanation,
            Command command
    ) implements StepResponse { }

    public record StepResponseQuestion(
            String version,
            String kind,
            String outcome,
            String explanation,
            Question question
    ) implements StepResponse { }

    public record StepResponseNeedContext(
            String version,
            String kind,
            String outcome,
            String explanation,
            List<LoaderRequest> requests
    ) implements StepResponse { }

    public record StepResponseNoChange(
            String version,
            String kind,
            String outcome,
            String explanation,
            List<String> goalIds
    ) implements StepResponse { }

    public record StepResponseUnsupported(
            String version,
            String kind,
            String outcome,
            String explanation,
            Diagnostic diagnostic
    ) implements StepResponse { }

    public record StepResponseReplanRequired(
            String version,
            String kind,
            String outcome,
            String explanation
    ) implements StepResponse { }

    public record StepState(
            String stepId,
            String intentId,
            String skillVersion,
            String schemaVersion,
            Long revision,
            List<String> dependsOn,
            List<Input> inputs,
            List<String> goalIds,
            String status,
            String reviewId,
            String receiptId,
            Diagnostic diagnostic
    ) { }

    public record PlanState(
            String version,
            String kind,
            String planId,
            String conversationId,
            Long revision,
            String status,
            List<StepState> steps,
            List<Fact> facts,
            List<Goal> goals,
            String policyId,
            String policyVersion,
            String budgetId,
            String expiresAt,
            PendingQuestion pendingQuestion,
            Focus focus
    ) { }

    public record Limits(
            Long maxCalls,
            Long maxInputTokens,
            Long maxOutputTokens,
            Long maxElapsedMs,
            Long maxCostMicros,
            String currency
    ) { }

    public record Candidate(
            String providerId,
            String modelConfigId,
            String qualificationId,
            Long contextWindow,
            Long maxInputTokens,
            Long maxOutputTokens,
            String outputMode,
            String priceSnapshotId
    ) { }

    public record RoutingPolicyProfilesItem(
            String profileId,
            List<String> intentIds,
            List<String> phases,
            List<Candidate> candidates,
            Boolean allowEscalation
    ) { }

    public record RoutingPolicy(
            String version,
            String kind,
            String policyId,
            String policyVersion,
            String mode,
            String pinnedModelConfigId,
            List<RoutingPolicyProfilesItem> profiles,
            Limits turnLimits,
            Limits planLimits,
            String unknownPriceBehavior,
            Long unknownCostUpperBoundMicros,
            BigDecimal successCostRatioAtMost,
            String referenceProfileId
    ) { }

    public record Usage(
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long reasoningTokens,
            Long costMicros
    ) { }

    public record BudgetStateAttemptsItem(
            String callId,
            String phase,
            String stepId,
            String providerId,
            String modelConfigId,
            String routeReason,
            String priceSnapshotId,
            Long estimatedInputTokens,
            Long reservedOutputTokens,
            Long reservedCostMicros,
            String status,
            Usage usage
    ) { }

    public record BudgetState(
            String version,
            String kind,
            String budgetId,
            String planId,
            String turnId,
            Long revision,
            Limits limits,
            Long callsStarted,
            Long reservedCostMicros,
            Long actualCostMicros,
            String status,
            List<BudgetStateAttemptsItem> attempts
    ) { }

    public record ConfirmationRequest(
            String version,
            String kind,
            String requestId,
            String stepId,
            String reviewId,
            Long expectedRevision,
            Long expectedVersion,
            String payloadDigest
    ) { }

    public record ControlRequest(
            String version,
            String kind,
            String requestId,
            Long expectedRevision,
            String action,
            String stepId
    ) { }

    public record TurnResponseConfirmation(
            String reviewId,
            Long version,
            String payloadDigest
    ) { }

    public record TurnResponse(
            String version,
            String kind,
            String requestId,
            String conversationId,
            Long revision,
            String planId,
            String stepId,
            String outcome,
            String message,
            String reviewId,
            Question question,
            Diagnostic diagnostic,
            TurnResponseConfirmation confirmation,
            String traceTurnId,
            String questionId
    ) { }

    public record GoalProposal(
            String goalId,
            String sourceMessageId,
            String text
    ) { }

    public record PriceSnapshotRates(
            BigDecimal inputMicrosPerMillion,
            BigDecimal cachedInputMicrosPerMillion,
            BigDecimal outputMicrosPerMillion,
            BigDecimal additionalMicrosPerCall
    ) { }

    public record PriceSnapshot(
            String version,
            String kind,
            String priceSnapshotId,
            String providerId,
            String modelConfigId,
            String currency,
            String effectiveAt,
            String expiresAt,
            String source,
            String sourceRef,
            PriceSnapshotRates rates,
            Boolean reasoningIncludedInOutput
    ) { }

    public record PendingQuestion(
            String questionId,
            String assistantMessageId,
            String stepId,
            Long askedAtRevision,
            Question question
    ) { }

    public record Focus(
            String deviceRef,
            String dashboardRef,
            String reviewId,
            String stepId,
            String origin
    ) { }

    public record DialogueMessagesItem(
            String messageId,
            Long sequence,
            String role,
            String kind,
            String text,
            String stepId,
            String reviewId,
            String questionId
    ) { }

    public record DialogueResourceEventsItem(
            String eventId,
            Long sequence,
            String sourceMessageId,
            String intentId,
            String stepId,
            List<String> resourceRefs,
            String receiptId,
            String status,
            String summary
    ) { }

    public record Dialogue(
            List<DialogueMessagesItem> messages,
            PendingQuestion pendingQuestion,
            Focus focus,
            List<DialogueResourceEventsItem> resourceEvents,
            String historyCompleteness,
            Long omittedMessageCount,
            String historyCursor
    ) { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BindingLiteral.class, name = "literal"),
        @JsonSubTypes.Type(value = BindingFactRef.class, name = "fact_ref"),
        @JsonSubTypes.Type(value = BindingStepOutput.class, name = "step_output")
    })
    public sealed interface Binding permits BindingLiteral, BindingFactRef, BindingStepOutput { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "outcome", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = StepResponsePrepared.class, name = "PREPARED"),
        @JsonSubTypes.Type(value = StepResponseQuestion.class, name = "QUESTION"),
        @JsonSubTypes.Type(value = StepResponseNeedContext.class, name = "NEED_CONTEXT"),
        @JsonSubTypes.Type(value = StepResponseNoChange.class, name = "NO_CHANGE"),
        @JsonSubTypes.Type(value = StepResponseUnsupported.class, name = "UNSUPPORTED"),
        @JsonSubTypes.Type(value = StepResponseReplanRequired.class, name = "REPLAN_REQUIRED")
    })
    public sealed interface StepResponse permits StepResponsePrepared, StepResponseQuestion, StepResponseNeedContext, StepResponseNoChange, StepResponseUnsupported, StepResponseReplanRequired { }

    public record AssessmentCheck(String sourceMessageId, String sourceQuote, String criterion, String verdict,
            List<String> widgetKeys, String evidence) { }

    public record AssessmentResponse(String version, String kind, String candidateDigest, String verdict,
            List<AssessmentCheck> checks) { }

}
