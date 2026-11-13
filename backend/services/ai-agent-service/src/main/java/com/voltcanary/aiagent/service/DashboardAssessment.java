package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels;
import com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Optional application assessment; structural verification remains in the existing pure verifier. */
public final class DashboardAssessment {
    private final ObjectMapper mapper;
    private final RoutedIntentModels models;
    private final AssistantTraceRecorder traces;

    public DashboardAssessment(ObjectMapper mapper, RoutedIntentModels models, AssistantTraceRecorder traces) {
        this.mapper = mapper;
        this.models = models;
        this.traces = traces;
    }

    public void verify(IntentPlans.ModelInput input, OrchestrationPorts.Session session, AgentPlan plan) {
        JsonNode definition = mapper.valueToTree(plan.actions().getFirst().parameters().get("definition"));
        if (!definition.isObject()) throw IntentPlans.reject("ASSESSMENT_CANDIDATE_MISSING");
        String digest = SkillRegistry.digest(definition.toString());
        var candidate = mapper.createObjectNode().put("candidateDigest", digest);
        candidate.set("definition", definition);
        UUID call = UUID.nameUUIDFromBytes((input.token().requestId() + ":assessment:" + digest).getBytes(StandardCharsets.UTF_8));
        var report = models.assess(input, session, call, candidate);
        validateEvidence(report, digest, definition);
        boolean accepted = report.verdict().equals("SATISFIED");
        traces.append(new AssistantTraceRecorder.Correlation(session.owner(), input.state().conversationId(),
                        input.currentMessage().messageId(), input.token().requestId(), input.token().requestId()),
                call, "OUTCOME_VERIFICATION", accepted ? "PASSED" : "FAILED", accepted ? null : "INTENT_OUTCOME_" + report.verdict(),
                null, Map.of("candidateDigest", digest, "verdict", report.verdict(), "checks", report.checks(),
                        "assessmentVersion", DashboardAssessmentGuide.VERSION));
        if (!accepted) throw new StepPreparationFailure(new AssistantProtocol.Diagnostic("OUTCOME", "INTENT_OUTCOME_" + report.verdict(),
                "Проверка соответствия запросу не пройдена: " + report.checks().stream()
                        .filter(check -> !check.verdict().equals("SATISFIED")).map(AssistantProtocol.AssessmentCheck::criterion)
                        .limit(4).collect(java.util.stream.Collectors.joining("; ")), java.util.List.of(), report.verdict().equals("REJECTED")));
    }

    public static void validateEvidence(AssistantProtocol.AssessmentResponse report, String digest, JsonNode definition) {
        if (!digest.equals(report.candidateDigest())) throw IntentPlans.reject("ASSESSMENT_DIGEST_CHANGED");
        var keys = new HashSet<String>();
        definition.path("widgets").forEach(widget -> keys.add(widget.path("id").asText()));
        if (report.checks().stream().anyMatch(check -> !keys.containsAll(check.widgetKeys())))
            throw IntentPlans.reject("ASSESSMENT_WIDGET_EVIDENCE_INVALID");
        if (report.verdict().equals("SATISFIED") != report.checks().stream().allMatch(check -> check.verdict().equals("SATISFIED")))
            throw IntentPlans.reject("ASSESSMENT_VERDICT_INCONSISTENT");
    }
}
