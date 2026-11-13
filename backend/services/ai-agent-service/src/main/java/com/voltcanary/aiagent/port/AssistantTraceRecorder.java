package com.voltcanary.aiagent.port;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AssistantTraceRecorder {
    record Correlation(Scope owner, UUID conversationId, UUID messageId, UUID turnId, UUID requestId) { }
    record Event(UUID id, int schemaVersion, long sequence, UUID conversationId, UUID messageId, UUID turnId,
            UUID requestId, UUID attemptId, UUID reviewId, UUID actionId, UUID modelRunId, String stage, String status,
            String code, Long durationMs, Map<String, Object> details, boolean truncated, Instant createdAt) { }

    void append(Correlation correlation, UUID attemptId, String stage, String status, String code,
            Long durationMs, Map<String, Object> details);
}
