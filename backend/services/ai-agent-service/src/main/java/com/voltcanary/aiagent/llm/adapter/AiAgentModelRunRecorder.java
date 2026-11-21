package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.domain.AiAgentModelRun;
import com.voltcanary.aiagent.repository.AiAgentModelRunRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiAgentModelRunRecorder {
    private final AiAgentModelRunRepository repository;

    public void record(UUID accountId, UUID userId, AiResolvedModel model, String status, long latencyMs, String errorCode, String errorMessage, UUID planId) {
        AiAgentModelRun run = new AiAgentModelRun();
        run.setId(UUID.randomUUID());
        run.setAccountId(accountId);
        run.setUserId(userId);
        run.setPlanId(planId);
        run.setProviderId(model.provider().getId());
        run.setModelConfigId(model.modelConfig().getId());
        run.setProviderType(model.provider().getProviderType().name());
        run.setModelId(model.modelConfig().getModelId());
        run.setStatus(status);
        run.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
        run.setErrorCode(errorCode);
        run.setErrorMessage(errorMessage == null ? null : errorMessage.substring(0, Math.min(1024, errorMessage.length())));
        run.setCreatedAt(Instant.now());
        repository.save(run);
        var trace = new java.util.LinkedHashMap<String, Object>();
        trace.put("modelRunId", run.getId());
        trace.put("reviewId", planId);
        trace.put("providerOutcome", status);
        trace.put("latencyMs", latencyMs);
        com.voltcanary.aiagent.trace.AssistantTrace.event("MODEL_RUN", "OBSERVED", errorCode, trace);
    }
}
