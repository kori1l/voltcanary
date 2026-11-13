package com.voltcanary.aiagent.orchestration.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Applies the collaboration wire contract under a verified root input turn. */
public final class RequirementAnswers {
    private final ProtocolValidator validator;
    private final RequirementChanges changes;

    public RequirementAnswers(ProtocolValidator validator, RequirementChanges changes) {
        this.validator = validator;
        this.changes = changes;
    }

    public Requirements.Snapshot apply(Requirements.Snapshot before, JsonNode body, IntentPlans.Token turn, Instant now) {
        validator.validate("collab.RequirementsAnswer", body);
        UUID plan = id(body, "planId");
        UUID request = id(body, "requestId");
        UUID question = id(body, "questionId");
        if (!before.planId().equals(plan) || !turn.planId().equals(plan) || !turn.requestId().equals(request)) {
            throw Requirements.conflict("REQUIREMENT_ANSWER_TURN_CHANGED");
        }
        if (turn.answeredQuestion() == null || !question.toString().equals(turn.answeredQuestion().questionId())) {
            throw Requirements.conflict("QUESTION_CHANGED");
        }
        long questionRevision = body.get("expectedQuestionRevision").longValue();
        long requirementsRevision = body.get("expectedRequirementsRevision").longValue();
        var answer = body.get("answer");
        return switch (answer.get("kind").textValue()) {
            case "CHOICE" -> changes.answer(before, question, questionRevision, requirementsRevision,
                    request, turn.messageId(), Map.of(), answer.get("choiceId").textValue(), now);
            case "TEXT" -> changes.answerText(before, question, questionRevision, requirementsRevision,
                    request, turn.messageId(), answer.get("text").textValue(), now);
            case "VALUES" -> changes.answer(before, question, questionRevision, requirementsRevision,
                    request, turn.messageId(), bindings(answer.get("bindings")), null, now);
            default -> throw Requirements.invalid("INVALID_REQUIREMENT_ANSWER");
        };
    }

    private static Map<UUID, JsonNode> bindings(JsonNode bindings) {
        var values = new LinkedHashMap<UUID, JsonNode>();
        for (var binding : bindings) {
            if (values.putIfAbsent(id(binding, "slotId"), binding.get("value")) != null) {
                throw Requirements.invalid("DUPLICATE_REQUIREMENT_SLOT");
            }
        }
        return values;
    }

    private static UUID id(JsonNode node, String field) {
        return UUID.fromString(node.get(field).textValue());
    }
}
