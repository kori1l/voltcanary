package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.voltcanary.aiagent.adapter.JdbcAssistantTraces;
import com.voltcanary.aiagent.config.ConversationProperties;
import com.voltcanary.aiagent.config.TraceProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AssistantTraceTest {

    @Test
    void identicalProviderUsageFromDistinctBudgetAttemptsNeverDeduplicates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var session = AssistantTrace.open(traces, correlation)) {
            AssistantTrace.modelAttempt(first);
            AssistantTrace.event(
                "PROVIDER_USAGE",
                "OBSERVED",
                null,
                Map.of("inputTokens", 3, "outputTokens", 2)
            );
            AssistantTrace.modelAttempt(second);
            AssistantTrace.event(
                "PROVIDER_USAGE",
                "OBSERVED",
                null,
                Map.of("inputTokens", 3, "outputTokens", 2)
            );
        }
        assertThat(
            jdbc.queryForList(
                "select attempt_id from ai_assistant_trace_events where stage='PROVIDER_USAGE'",
                UUID.class
            )
        ).containsExactlyInAnyOrder(first, second);
    }

    private JdbcTemplate jdbc;
    private ConversationStore conversations;
    private JdbcAssistantTraces traces;
    private AssistantTraceRecorder.Correlation correlation;
    private final Scope owner = new Scope(UUID.randomUUID(), UUID.randomUUID());
    private final TraceProperties properties = new TraceProperties();

    @BeforeEach
    void database() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create domain if not exists timestamptz as timestamp with time zone");
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V002__durable_reviewed_plans.sql"),
            new ClassPathResource("db/migration/V004__assistant_conversations.sql"),
            new ClassPathResource("db/migration/V005__assistant_message_provenance.sql"),
            new ClassPathResource("db/migration/V006__assistant_trace_events.sql")
        ).execute(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var mapper = JsonMapper.builder().findAndAddModules().build();
        conversations = new ConversationStore(
            jdbc,
            transactions,
            new ConversationProperties(),
            mapper
        );
        traces = new JdbcAssistantTraces(jdbc, transactions, conversations, properties, mapper);
        UUID conversation = conversations.create(owner, UUID.randomUUID()).id();
        var request = new ConversationContracts.Submit(
            UUID.randomUUID(),
            0,
            "bus22",
            Map.of(),
            null,
            null,
            null,
            null,
            false,
            null
        );
        conversations.begin(owner, conversation, request, "hash");
        correlation = traces.input(owner, conversation, request.requestId());
    }

    @Test
    void rejectedCandidatesRetainFieldNamesButNeverCredentialsOrPrivateReasoning() {
        try (
            var session = AssistantTrace.open(traces, correlation);
            var call = AssistantTrace.start("MODEL_CALL", Map.of("model", "fixture"))
        ) {
            AssistantTrace.event(
                "CANDIDATE",
                "OBSERVED",
                null,
                Map.of(
                    "parameters",
                    Map.of("dataRefs", "fabricated", "apiKey", "hidden-value"),
                    "reasoning",
                    "private-thought",
                    "text",
                    "password=hunter2 Bearer xyz",
                    "promptTokens",
                    17
                )
            );
            AssistantTrace.event(
                "SANITIZATION",
                "FAILED",
                "UNSUPPORTED_FIELDS",
                Map.of("fieldPaths", java.util.List.of("dataRefs"))
            );
            call.failure("INVALID_CANDIDATE", Map.of());
        }
        String persisted = String.join(
            "\n",
            jdbc.queryForList("select payload_json from ai_assistant_trace_events", String.class)
        );
        assertThat(persisted)
            .contains("dataRefs", "fabricated", "promptTokens")
            .doesNotContain("hidden-value", "private-thought", "hunter2", "xyz");
        var events = traces
            .read(owner, correlation.conversationId(), correlation.turnId(), 0, 100)
            .items();
        assertThat(events).hasSize(4);
        assertThat(events.getFirst().status()).isEqualTo("STARTED");
        assertThat(events.getLast().status()).isEqualTo("FAILED");
        assertThat(events.get(2).code()).isEqualTo("UNSUPPORTED_FIELDS");
        assertThat(events).allMatch(
            event ->
                event.requestId().equals(correlation.requestId()) &&
                event.messageId().equals(correlation.messageId())
        );
    }

    @Test
    void boundedPayloadsRetainExplicitTruncationAndCrossAccountReadsAreDenied() {
        properties.setMaxPayloadBytes(512);
        traces.append(
            correlation,
            UUID.randomUUID(),
            "CAPABILITIES",
            "OBSERVED",
            null,
            null,
            Map.of(
                "fields",
                java.util.stream.IntStream.range(0, 40)
                    .mapToObj(index -> "field" + index + "x".repeat(100))
                    .toList()
            )
        );
        var page = traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 1);
        assertThat(page.items().getFirst().truncated()).isTrue();
        assertThat(page.items().getFirst().details()).containsKey("sha256");
        assertThat(
            jdbc.queryForObject(
                "select payload_bytes from ai_assistant_trace_events",
                Integer.class
            )
        ).isLessThanOrEqualTo(512);
        var bounded = new com.voltcanary.aiagent.trace.TracePayload(
            JsonMapper.builder().build()
        ).encode(Map.of("\u0001".repeat(1000), "x".repeat(8000)), 512);
        assertThat(bounded.bytes()).isLessThanOrEqualTo(512);
        assertThatThrownBy(() ->
            traces.read(
                new Scope(UUID.randomUUID(), UUID.randomUUID()),
                correlation.conversationId(),
                correlation.turnId(),
                0,
                100
            )
        ).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void restartReconcilesInterruptedStagesOnceWithoutReplayingWork() {
        UUID attempt = UUID.randomUUID();
        traces.append(correlation, attempt, "MODEL_CALL", "STARTED", null, null, Map.of());
        traces.append(correlation, attempt, "MODEL_CALL", "STARTED", null, null, Map.of());
        assertThat(
            traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 100).items()
        ).hasSize(1);
        jdbc.update(
            "update ai_assistant_trace_events set created_at=?",
            Timestamp.from(Instant.now().minusSeconds(601))
        );
        assertThat(
            traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 100).items()
        ).hasSize(2);
        assertThat(
            traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 100).items()
        ).hasSize(2);
        assertThat(
            traces
                .read(owner, correlation.conversationId(), correlation.turnId(), 0, 100)
                .items()
                .getLast()
                .code()
        ).isEqualTo("PROCESS_INTERRUPTED");
    }

    @Test
    void failedRequiredStartBlocksSideEffectsButFailedOutcomeDoesNotRepeatThem() {
        int[] calls = { 0 };
        AssistantTraceRecorder broken = (
            context,
            attempt,
            stage,
            status,
            code,
            duration,
            details
        ) -> {
            throw new IllegalStateException("storage unavailable");
        };
        try (var session = AssistantTrace.open(broken, correlation)) {
            assertThatThrownBy(() -> {
                try (var action = AssistantTrace.start("TOOL_EXECUTION", Map.of())) {
                    calls[0]++;
                }
            }).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls[0]).isZero();
        AssistantTraceRecorder failAfterStart = (
            context,
            attempt,
            stage,
            status,
            code,
            duration,
            details
        ) -> {
            if (!"STARTED".equals(status)) throw new IllegalStateException("outcome unavailable");
        };
        try (
            var session = AssistantTrace.open(failAfterStart, correlation);
            var action = AssistantTrace.start("TOOL_EXECUTION", Map.of())
        ) {
            calls[0]++;
            action.success(Map.of("executionStatus", "SUCCESS"));
            assertThatThrownBy(() -> {
                try (var next = AssistantTrace.start("TOOL_EXECUTION", Map.of())) {
                    calls[0]++;
                }
            }).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void interruptedExecutionUsesAnExistingScopedPartialReceiptWithoutCallingTools() {
        UUID plan = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
            """
            insert into ai_reviewed_plans(id,user_id,account_id,version,payload_digest,plan_json,context_json,
            status,created_at,expires_at,updated_at,retain_until) values(?,?,?,1,'hash','{}','{}','PARTIAL_SUCCESS',?,?,?,?)
            """,
            plan,
            owner.userId(),
            owner.accountId(),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(86400))
        );
        traces.append(
            correlation,
            UUID.randomUUID(),
            "EXECUTION",
            "STARTED",
            null,
            null,
            Map.of("reviewId", plan)
        );
        jdbc.update(
            "update ai_assistant_trace_events set created_at=?",
            Timestamp.from(now.minusSeconds(601))
        );
        var events = traces
            .read(owner, correlation.conversationId(), correlation.turnId(), 0, 100)
            .items();
        assertThat(events.getLast().code()).isEqualTo("RECEIPT_RECOVERY");
        assertThat(events.getLast().status()).isEqualTo("FAILED");
        assertThat(events.getLast().details())
            .containsEntry("executionStatus", "PARTIAL_SUCCESS")
            .containsEntry("replayed", false);
        assertThat(
            traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 100).items()
        ).hasSize(2);
    }

    @Test
    void paginationLimitsAndRetentionKeepTraceVolumeFinite() {
        properties.setMaxEvents(16);
        for (int index = 0; index < 15; index++) traces.append(
            correlation,
            UUID.randomUUID(),
            index == 0 ? "MODEL_CALL" : "CHECK",
            index == 0 ? "STARTED" : "OBSERVED",
            null,
            null,
            Map.of("index", index)
        );
        assertThatThrownBy(() ->
            traces.append(correlation, UUID.randomUUID(), "CHECK", "OBSERVED", null, null, Map.of())
        ).isInstanceOf(ResponseStatusException.class);
        jdbc.update(
            "update ai_assistant_trace_events set created_at=?",
            Timestamp.from(Instant.now().minusSeconds(601))
        );
        assertThat(
            traces
                .read(owner, correlation.conversationId(), correlation.turnId(), 0, 100)
                .items()
                .getLast()
                .code()
        ).isEqualTo("CAPTURE_EVENT_LIMIT");
        var first = traces.read(owner, correlation.conversationId(), correlation.turnId(), 0, 5);
        var next = traces.read(
            owner,
            correlation.conversationId(),
            correlation.turnId(),
            Long.parseLong(first.nextCursor()),
            5
        );
        assertThat(next.items().getFirst().sequence()).isGreaterThan(
            first.items().getLast().sequence()
        );
        jdbc.update(
            "update ai_conversations set expires_at=?",
            Timestamp.from(Instant.now().minusSeconds(1))
        );
        conversations.purge();
        assertThat(
            jdbc.queryForObject("select count(*) from ai_assistant_trace_events", Integer.class)
        ).isZero();
    }
}
