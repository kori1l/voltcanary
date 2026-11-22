package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FailedModelUsageTraceTest {

    @Test
    void malformedFunctionalResponseRetainsUsageInLedgerAndFailedTraceWithoutRepeatingExchange() {
        var mapper = new ObjectMapper();
        var ledger = mock(AttemptLedger.class);
        var turn = new AttemptLedger.Turn(
            new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        String body = "{}";
        var route = new ModelRouting.Route(
            new AssistantProtocol.Candidate(
                "provider",
                "model",
                null,
                128000L,
                112000L,
                12000L,
                "JSON_TEXT",
                null
            ),
            ModelRouting.estimate(body, 12000, 4096),
            100000,
            null,
            "test"
        );
        var attempt = new AttemptLedger.Attempt(
            UUID.randomUUID(),
            "PLANNER",
            null,
            SkillRegistry.digest(body),
            "policy",
            "1",
            "1",
            "1",
            "1",
            route
        );
        when(ledger.reserve(turn, attempt)).thenReturn(
            new AttemptLedger.Receipt(true, "STARTED", null)
        );
        when(ledger.remainingTimeMillis(turn)).thenReturn(1000L);
        var outcomes = new ArrayList<Map<String, Object>>();
        AssistantTraceRecorder traces = (
            correlation,
            call,
            stage,
            status,
            code,
            duration,
            details
        ) -> {
            if (stage.equals("MODEL_CALL") && status.equals("FAILED")) outcomes.add(details);
        };
        var usage = new AssistantProtocol.Usage(123L, 12L, 45L, 20L, null);
        var exchange = mock(BudgetedModelCalls.SingleExchange.class, CALLS_REAL_METHODS);
        try {
            when(
                exchange.send(
                    eq(body),
                    eq(route),
                    longThat(timeout -> timeout > 0 && timeout <= 1000)
                )
            ).thenReturn(new BudgetedModelCalls.Reply("JSON_TEXT", "{}", usage));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        when(exchange.normalize(any(), eq(body))).thenAnswer(invocation ->
            invocation.getArgument(0)
        );
        var correlation = new AssistantTraceRecorder.Correlation(
            turn.owner(),
            turn.conversationId(),
            UUID.randomUUID(),
            turn.turnId(),
            UUID.randomUUID()
        );
        assertThrows(ModelProposalRejected.class, () ->
            new BudgetedModelCalls(ledger, new ProtocolValidator(mapper), traces).call(
                turn,
                attempt,
                body,
                "plannerResponse",
                correlation,
                exchange,
                ignored -> {}
            )
        );
        assertEquals(1, outcomes.size());
        assertEquals(usage, outcomes.getFirst().get("usage"));
        assertEquals(
            Map.of(
                "jsonObject",
                true,
                "hasQuestion",
                false,
                "hasCommand",
                false,
                "stepCount",
                0,
                "operationCount",
                0
            ),
            outcomes.getFirst().get("proposalShape")
        );
        verify(ledger).finish(turn, attempt.callId(), "FAILED", usage, null);
        try {
            verify(exchange, times(1)).send(
                eq(body),
                eq(route),
                longThat(timeout -> timeout > 0 && timeout <= 1000)
            );
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
