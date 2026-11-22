package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.time.Clock;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** A2 application seam; A3/A4 provide durable turns and real bounded context, A5 provides production skills. */
public final class RoutedModelCalls {

    public interface WireAdapter extends BudgetedModelCalls.SingleExchange {
        default JsonNode prepareContext(
            JsonNode request,
            AttemptLedger ledger,
            AttemptLedger.Turn turn,
            RoutingPolicy policy,
            UUID callId,
            String phase
        ) {
            return request;
        }

        /** Pure serialization of the complete model exchange, including options, schema/tools and mandatory dialogue.
         * SDK adapters may lower this losslessly to provider messages; reserve their framing allowance as well.
         * No I/O, semantic rewriting, truncation, fallback or retry is allowed here.
         */
        String serialize(JsonNode request, JsonNode responseSchema, String outputMode);

        default String serializeCorrection(
            JsonNode request,
            JsonNode responseSchema,
            String outputMode,
            ModelProposalRejected rejected
        ) {
            throw new ModelRouting.Stop("FORMAT_CORRECTION_UNAVAILABLE");
        }
    }

    public record Selection(
        String profileId,
        String phase,
        String previousModel,
        long outputIncludingReasoning,
        long framingAllowance
    ) {}

    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final SkillRegistry registry;
    private final AttemptLedger ledger;
    private final BudgetedModelCalls calls;
    private final Clock clock;

    public RoutedModelCalls(
        ObjectMapper mapper,
        ProtocolValidator validator,
        SkillRegistry registry,
        AttemptLedger ledger,
        AssistantTraceRecorder traces,
        Clock clock
    ) {
        this.mapper = mapper;
        this.validator = validator;
        this.registry = registry;
        this.ledger = ledger;
        this.calls = new BudgetedModelCalls(ledger, validator, traces);
        this.clock = clock;
    }

    public JsonNode call(
        AttemptLedger.Turn turn,
        UUID callId,
        JsonNode policyDocument,
        JsonNode request,
        Selection selection,
        Set<String> authorizedIntents,
        Set<String> capabilities,
        ModelRouting.Availability availability,
        AssistantTraceRecorder.Correlation correlation,
        WireAdapter adapter
    ) {
        return call(
            turn,
            callId,
            policyDocument,
            request,
            selection,
            authorizedIntents,
            capabilities,
            availability,
            correlation,
            adapter,
            null
        );
    }

    public JsonNode call(
        AttemptLedger.Turn turn,
        UUID callId,
        JsonNode policyDocument,
        JsonNode request,
        Selection selection,
        Set<String> authorizedIntents,
        Set<String> capabilities,
        ModelRouting.Availability availability,
        AssistantTraceRecorder.Correlation correlation,
        WireAdapter adapter,
        ModelProposalRejected correction
    ) {
        return call(
            turn,
            callId,
            policyDocument,
            request,
            selection,
            authorizedIntents,
            capabilities,
            availability,
            correlation,
            adapter,
            correction,
            proposal -> {}
        );
    }

    public JsonNode call(
        AttemptLedger.Turn turn,
        UUID callId,
        JsonNode policyDocument,
        JsonNode request,
        Selection selection,
        Set<String> authorizedIntents,
        Set<String> capabilities,
        ModelRouting.Availability availability,
        AssistantTraceRecorder.Correlation correlation,
        WireAdapter adapter,
        ModelProposalRejected correction,
        Consumer<JsonNode> applicationCheck
    ) {
        return call(
            turn,
            callId,
            policyDocument,
            request,
            selection,
            authorizedIntents,
            capabilities,
            availability,
            correlation,
            adapter,
            correction,
            applicationCheck,
            prepared -> {}
        );
    }

    public JsonNode call(
        AttemptLedger.Turn turn,
        UUID callId,
        JsonNode policyDocument,
        JsonNode request,
        Selection selection,
        Set<String> authorizedIntents,
        Set<String> capabilities,
        ModelRouting.Availability availability,
        AssistantTraceRecorder.Correlation correlation,
        WireAdapter adapter,
        ModelProposalRejected correction,
        Consumer<JsonNode> applicationCheck,
        Consumer<JsonNode> verifyAccess
    ) {
        RoutingPolicy policy = validator.read("routingPolicy", policyDocument, RoutingPolicy.class);
        boolean planner = request.path("kind").asText().equals("planner.request");
        String responseDefinition;
        JsonNode responseSchema;
        String intent;
        String skillVersion;
        Consumer<JsonNode> check;
        if (planner) {
            if (!selection.phase().equals("PLANNER")) throw new ModelRouting.Stop("PHASE_MISMATCH");
            validator.validate("plannerRequest", request);
            if (
                !mapper
                    .valueToTree(registry.catalogue(authorizedIntents, capabilities))
                    .equals(request.path("catalogue"))
            ) throw new ModelRouting.Stop("CATALOGUE_MISMATCH");
            intent = "";
            skillVersion = "catalogue-v1";
            responseDefinition = "plannerResponse";
            responseSchema = validator.projection(responseDefinition);
            check = response -> registry.validatePlanner(response, authorizedIntents, capabilities);
        } else {
            if (
                !Set.of("STEP", "REPAIR", "VERIFY").contains(selection.phase())
            ) throw new ModelRouting.Stop("PHASE_MISMATCH");
            var manifest = registry.validateStepRequest(request, authorizedIntents, capabilities);
            if (
                !manifest.modelProfileId().equals(selection.profileId())
            ) throw new ModelRouting.Stop("PROFILE_MISMATCH");
            intent = manifest.intentId();
            skillVersion = manifest.skillVersion();
            boolean verifying = selection.phase().equals("VERIFY");
            responseDefinition = verifying ? "assessmentResponse" : "stepResponse";
            responseSchema = verifying
                ? validator.projection(responseDefinition)
                : registry.responseProjection(intent, authorizedIntents, capabilities);
            check = verifying
                ? response -> validateAssessment(request, response)
                : response ->
                      registry.validateStep(request, response, authorizedIntents, capabilities);
        }
        ledger.open(turn, policy);
        JsonNode prepared = adapter.prepareContext(
            request.deepCopy(),
            ledger,
            turn,
            policy,
            callId,
            selection.phase()
        );
        var bodies = new HashMap<String, String>();
        var route = ModelRouting.select(
            policy,
            selection.profileId(),
            intent,
            selection.phase(),
            candidate -> {
                String body =
                    correction == null
                        ? adapter.serialize(
                              prepared.deepCopy(),
                              responseSchema.deepCopy(),
                              candidate.outputMode()
                          )
                        : adapter.serializeCorrection(
                              prepared.deepCopy(),
                              responseSchema.deepCopy(),
                              candidate.outputMode(),
                              correction
                          );
                bodies.put(candidate.modelConfigId(), body);
                return ModelRouting.estimate(
                    body,
                    selection.outputIncludingReasoning(),
                    selection.framingAllowance()
                );
            },
            availability,
            ledger.remainingCost(turn),
            selection.previousModel(),
            clock.instant()
        );
        String body = bodies.get(route.candidate().modelConfigId());
        var attempt = new AttemptLedger.Attempt(
            callId,
            selection.phase(),
            planner ? null : request.path("stepId").asText(),
            SkillRegistry.digest(body),
            policy.policyId(),
            policy.policyVersion(),
            skillVersion,
            "assistant-v2",
            planner ? "dialogue-v1" : request.at("/context/projectionVersion").asText(),
            route,
            selection.phase().equals("VERIFY")
                ? "OUTCOME_ASSESSMENT"
                : correction != null
                  ? "FORMAT_CORRECTION"
                  : selection.phase().equals("REPAIR")
                    ? "OUTCOME".equals(request.at("/repair/category").asText())
                        ? "OUTCOME_RECOVERY"
                        : "DOMAIN_RECOVERY"
                    : !request.at("/context/contextRequests").isEmpty() &&
                        request.at("/context/contextRequests").isArray()
                      ? "CONTEXT_ACQUISITION"
                      : "PRIMARY"
        );
        return calls.call(
            turn,
            attempt,
            body,
            responseDefinition,
            correlation,
            adapter,
            proposal -> {
                check.accept(proposal);
                if (
                    correction != null &&
                    planner &&
                    !proposal
                        .path("supersedesGoalIds")
                        .equals(correction.proposal().path("supersedesGoalIds")) &&
                    !proposal.path("supersedesGoalIds").isEmpty()
                ) throw new ModelRouting.Stop("TECHNICAL_REPAIR_CANNOT_SUPERSEDE_GOALS");
                applicationCheck.accept(proposal);
            },
            () -> verifyAccess.accept(prepared)
        );
    }

    private void validateAssessment(JsonNode request, JsonNode response) {
        var candidates = new java.util.ArrayList<JsonNode>();
        for (var loaded : request.path("loadResults"))
            if (loaded.path("loaderId").asText().equals("verification.candidate")) candidates.add(
                loaded.path("data")
            );
        if (
            candidates.size() != 1 ||
            !response.path("candidateDigest").equals(candidates.getFirst().path("candidateDigest"))
        ) throw new ModelRouting.Stop("ASSESSMENT_CANDIDATE_CHANGED");
        String violation =
            com.voltcanary.aiagent.orchestration.contract.AssessmentSources.violation(
                request,
                response
            );
        if (violation != null) throw new ModelRouting.Stop(violation);
    }

    /** Consultation exchanges use the original plan/turn reservation and the same single-exchange transport. */
    public JsonNode consult(
        AttemptLedger.Turn turn,
        JsonNode policyDocument,
        Selection selection,
        com.voltcanary.aiagent.orchestration.collaboration.ConsultationContracts contracts,
        com.voltcanary.aiagent.orchestration.collaboration.Consultations.Task task,
        com.voltcanary.aiagent.orchestration.collaboration.Requirements.Snapshot requirements,
        java.util.Map<UUID, JsonNode> inputs,
        java.util.List<JsonNode> childResults,
        java.util.List<JsonNode> loaderResults,
        Set<UUID> authorizedEvidence,
        ModelRouting.Availability availability,
        AssistantTraceRecorder.Correlation correlation,
        WireAdapter adapter
    ) {
        if (
            !"CONSULT".equals(selection.phase()) ||
            !turn.planId().equals(requirements.planId()) ||
            task.assignment() == null ||
            !turn.turnId().equals(task.assignment().originatingTurnId())
        ) {
            throw new ModelRouting.Stop("CONSULTATION_TURN_OR_PHASE_MISMATCH");
        }
        var policy = validator.read("routingPolicy", policyDocument, RoutingPolicy.class);
        var role = contracts.role(task.request());
        var request = com.voltcanary.aiagent.orchestration.collaboration.ConsultationPrompt.request(
            mapper,
            task,
            role,
            requirements,
            inputs
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) request).set(
            "capabilities",
            contracts.capabilities(role)
        );
        if (!childResults.isEmpty()) ((com.fasterxml.jackson.databind.node.ObjectNode) request).set(
            "childResults",
            mapper.valueToTree(childResults)
        );
        if (!loaderResults.isEmpty()) (
            (com.fasterxml.jackson.databind.node.ObjectNode) request
        ).set("loaderResults", mapper.valueToTree(loaderResults));
        validator.parse(request.toString());
        var schema =
            com.voltcanary.aiagent.orchestration.collaboration.ConsultationPrompt.responseSchema(
                validator,
                role,
                task,
                requirements,
                authorizedEvidence
            );
        ledger.open(turn, policy);
        var bodies = new HashMap<String, String>();
        var route = ModelRouting.select(
            policy,
            selection.profileId(),
            role.id(),
            "CONSULT",
            candidate -> {
                String body = adapter.serialize(
                    request.deepCopy(),
                    schema.deepCopy(),
                    candidate.outputMode()
                );
                bodies.put(candidate.modelConfigId(), body);
                return ModelRouting.estimate(
                    body,
                    selection.outputIncludingReasoning(),
                    selection.framingAllowance()
                );
            },
            availability,
            ledger.remainingCost(turn),
            selection.previousModel(),
            clock.instant()
        );
        String body = bodies.get(route.candidate().modelConfigId());
        var attempt = new AttemptLedger.Attempt(
            task.assignment().attemptId(),
            "CONSULT",
            task.id().toString(),
            SkillRegistry.digest(body),
            policy.policyId(),
            policy.policyVersion(),
            role.version(),
            "ASSISTANT-COLLAB/1",
            task.assignment().reads().digest(),
            route,
            "CONSULTATION"
        );
        return calls.call(
            turn,
            attempt,
            body,
            "collab.WorkerResponse",
            correlation,
            adapter,
            response -> contracts.response(task, response, requirements, authorizedEvidence)
        );
    }
}
