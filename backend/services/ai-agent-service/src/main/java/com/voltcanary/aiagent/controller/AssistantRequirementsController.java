package com.voltcanary.aiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.adapter.JdbcCollaborationStore;
import com.voltcanary.aiagent.adapter.JdbcIntentPlanStore;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementAnswers;
import com.voltcanary.aiagent.orchestration.collaboration.RequirementChanges;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PrimaryAssistant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent/plans/{planId}")
public final class AssistantRequirementsController {

    private final JdbcCollaborationStore collaboration;
    private final JdbcIntentPlanStore plans;
    private final JwtIdentityService identity;
    private final AuthorizedPlanningContext contexts;
    private final PrimaryAssistant assistant;
    private final RequirementAnswers answers;

    public record Cancellation(Long expectedTaskRevision, String requestKey) {}

    public record ConsultationStatus(
        UUID id,
        Consultations.Status status,
        long revision,
        int exchanges,
        java.util.List<UUID> children
    ) {}

    public AssistantRequirementsController(
        JdbcCollaborationStore collaboration,
        JdbcIntentPlanStore plans,
        JwtIdentityService identity,
        AuthorizedPlanningContext contexts,
        PrimaryAssistant assistant,
        ProtocolValidator validator
    ) {
        this.collaboration = collaboration;
        this.plans = plans;
        this.identity = identity;
        this.contexts = contexts;
        this.assistant = assistant;
        this.answers = new RequirementAnswers(
            validator,
            new RequirementChanges((schema, value) -> {
                validator.validate("collab.VclLiteral", value);
                if (!"text-v1".equals(schema) || !"TEXT".equals(value.path("type").asText())) {
                    throw Requirements.invalid("SLOT_TYPE_MISMATCH");
                }
            })
        );
    }

    @GetMapping("/requirements")
    public Requirements.Snapshot inspect(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId
    ) {
        return collaboration.read(scope(authorization), planId).requirements();
    }

    @PostMapping("/requirements/answers")
    public Requirements.Snapshot answer(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId,
        @RequestBody JsonNode body
    ) {
        var owner = scope(authorization);
        if (!assistant.enabled()) throw new Requirements.Rejected(
            503,
            "ASSISTANT_GENERATION_DISABLED"
        );
        if (!body.has("expectedQuestionRevision") || !body.has("expectedRequirementsRevision")) {
            throw new Requirements.Rejected(428, "REQUIREMENT_REVISIONS_REQUIRED");
        }
        try {
            plans.submitRequirementAnswer(collaboration, answers, owner, planId, body);
        } catch (IllegalArgumentException invalid) {
            if (invalid instanceof Requirements.Rejected rejected) throw rejected;
            throw Requirements.invalid("INVALID_REQUIREMENT_ANSWER");
        }
        return collaboration.read(owner, planId).requirements();
    }

    @GetMapping("/consultations/{taskId}")
    public ConsultationStatus consultation(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId,
        @PathVariable("taskId") UUID taskId
    ) {
        var task = collaboration
            .read(scope(authorization), planId)
            .consultations()
            .tasks()
            .get(taskId);
        if (task == null) throw new Requirements.Rejected(404, "CONSULTATION_UNAVAILABLE");
        return status(task);
    }

    @GetMapping("/consultations")
    public java.util.List<ConsultationStatus> consultations(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId
    ) {
        return collaboration
            .read(scope(authorization), planId)
            .consultations()
            .tasks()
            .values()
            .stream()
            .sorted(java.util.Comparator.comparing(Consultations.Task::id))
            .map(AssistantRequirementsController::status)
            .toList();
    }

    @PostMapping("/consultations/{taskId}/cancel")
    public ConsultationStatus cancel(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId,
        @PathVariable("taskId") UUID taskId,
        @RequestBody Cancellation request
    ) {
        var owner = scope(authorization);
        if (request.expectedTaskRevision() == null) throw new Requirements.Rejected(
            428,
            "CONSULTATION_REVISION_REQUIRED"
        );
        return status(
            collaboration.cancel(
                owner,
                planId,
                taskId,
                request.expectedTaskRevision(),
                request.requestKey()
            )
        );
    }

    private static ConsultationStatus status(Consultations.Task task) {
        return new ConsultationStatus(
            task.id(),
            task.status(),
            task.revision(),
            task.exchanges(),
            task.children()
        );
    }

    @ExceptionHandler(Requirements.Rejected.class)
    ResponseEntity<Map<String, String>> rejected(Requirements.Rejected exception) {
        return ResponseEntity.status(exception.status()).body(
            Map.of("code", exception.getMessage())
        );
    }

    private AuthorizedPlanningContext.Scope scope(String authorization) {
        return contexts.scope(identity.parseBearerToken(authorization), authorization);
    }
}
