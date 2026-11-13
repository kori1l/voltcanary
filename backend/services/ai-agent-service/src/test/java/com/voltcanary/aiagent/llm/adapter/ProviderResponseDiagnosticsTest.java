package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import com.voltcanary.aiagent.trace.AssistantTrace;
import com.voltcanary.aiagent.trace.TracePayload;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderResponseDiagnosticsTest {
    @Test
    void incompleteOutputRetainsCountersWithoutPrivateTextOrProviderBody() throws Exception {
        var mapper = JsonMapper.builder().build();
        var captured = new ArrayList<Map<String, Object>>();
        AssistantTraceRecorder recorder = (correlation, attempt, stage, status, code, duration, details) -> captured.add(details);
        var correlation = new AssistantTraceRecorder.Correlation(new Scope(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (var session = AssistantTrace.open(recorder, correlation)) {
            ProviderResponseDiagnostics.record(mapper.readTree("""
                    {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                    "usage":{"input_tokens":4200,"output_tokens":32000,"output_tokens_details":{"reasoning_tokens":31980}},
                    "output":[{"type":"reasoning","summary":[{"text":"PRIVATE-MUST-NOT-APPEAR"}]},
                    {"type":"message","content":[{"type":"output_text","text":"PARTIAL-OUTPUT-MUST-NOT-APPEAR"}]}],
                    "headers":{"Authorization":"Bearer private"}}
                    """), 32000);
        }
        assertThat(captured).singleElement().satisfies(details -> {
            assertThat(details).containsEntry("providerStatus", "incomplete").containsEntry("incompleteReason", "max_output_tokens")
                    .containsEntry("outputTokens", 32000L).containsEntry("internalTokenCount", 31980L);
            String json = new TracePayload(mapper).encode(details, 8192).json();
            assertThat(json).contains("internalTokenCount").doesNotContain("PRIVATE-MUST", "PARTIAL-OUTPUT", "Authorization", "Bearer");
        });
    }
}
