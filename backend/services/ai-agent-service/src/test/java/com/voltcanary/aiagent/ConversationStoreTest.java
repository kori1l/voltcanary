package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.voltcanary.aiagent.config.ConversationProperties;
import com.voltcanary.aiagent.conversation.AssistantConversations;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationRedaction;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.DashboardTaskConversation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ConversationStoreTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ConversationStore store;
    private final AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "owner@example.test", "USER");

    private final com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope owner = new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(actor.userId(), UUID.randomUUID());
    @BeforeEach
    void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(source).execute("create domain if not exists timestamptz as timestamp with time zone");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V002__durable_reviewed_plans.sql"),
                new ClassPathResource("db/migration/V004__assistant_conversations.sql"),
                new ClassPathResource("db/migration/V005__assistant_message_provenance.sql"),
                new ClassPathResource("db/migration/V006__assistant_trace_events.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        store = new ConversationStore(jdbc, transactions, new ConversationProperties(), JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void restartAndPaginationPreserveIdsOrderSelectionAndFailure() {
        UUID id = UUID.randomUUID();
        store.create(owner, id);
        var request = request(0, "Создай bus22");
        assertThat(store.begin(owner, id, request, "hash").claimed()).isTrue();
        store.finish(owner, id, request.requestId(), null, null, "Provider unavailable", "FAILED");
        var first = store.messages(owner, id, 0, 1);
        var restarted = new ConversationStore(jdbc, transactions, new ConversationProperties(), JsonMapper.builder().findAndAddModules().build());
        var second = restarted.messages(owner, id, Long.parseLong(first.nextCursor()), 1);
        assertThat(first.items().getFirst().role()).isEqualTo("user");
        assertThat(second.items().getFirst().outcome()).isEqualTo("FAILED");
        assertThat(restarted.messages(owner, id, 0, 100).items()).hasSize(2);
        assertThat(restarted.messages(owner, id, 0, 1).items().getFirst().id()).isEqualTo(first.items().getFirst().id());
        assertThat(restarted.selected(owner)).isEqualTo(id);
        assertThat(restarted.get(owner, id).revision()).isEqualTo(2);
    }

    @Test
    void duplicateRequestDoesNotAppendAndDifferentPayloadOrRevisionConflicts() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var request = request(0, "Create");
        assertThat(store.begin(owner, id, request, "hash").claimed()).isTrue();
        assertThat(store.begin(owner, id, request, "hash").claimed()).isFalse();
        assertThatThrownBy(() -> store.begin(owner, id, request, "different")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.begin(owner, id, request(0, "Other tab"), "other")).isInstanceOf(ResponseStatusException.class);
        assertThat(store.messages(owner, id, 0, 100).items()).hasSize(1);
    }

    @Test
    void concurrentClientsClaimExactlyOnce() throws Exception {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            var request = request(0, "Concurrent");
            calls.add(() -> {
                try { return store.begin(owner, id, request, request.requestId().toString()).claimed(); }
                catch (ResponseStatusException conflict) { return false; }
            });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            int winners = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.messages(owner, id, 0, 100).items()).hasSize(1);
    }

    @Test
    void crossOwnerCannotReadWriteSelectOrUseAnotherOwnersCursor() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var other = new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> store.get(other, id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.create(other, id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.messages(other, id, 0, 100)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.begin(other, id, request(0, "Bad"), "hash")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.select(other, id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.list(other, id, 10)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.list(other, null, 10).items()).isEmpty();
    }

    @Test
    void redactsCredentialsAndNestedProviderPayloadsBeforePersistence() throws Exception {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var request = request(0, "bus password=hunter2 api_key: abcdef Bearer xyz.123");
        store.begin(owner, id, request, "hash");
        var message = store.messages(owner, id, 0, 100).items().getFirst();
        assertThat(message.text()).doesNotContain("hunter2", "abcdef", "xyz.123");
        assertThat(message.redacted()).isTrue();
        assertThat(store.get(owner, id).title()).doesNotContain("hunter2");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var safe = ConversationRedaction.value(new ConversationContracts.Submit(UUID.randomUUID(), 0, "Hi", Map.of("apiKey", "hidden", "nested", Map.of("message", "secret: value")), null, null, null, null, false, null), ConversationContracts.Submit.class, mapper);
        assertThat(mapper.writeValueAsString(safe)).doesNotContain("hidden", "value");
        assertThat(ConversationRedaction.text("token=bare-value AIza" + "x".repeat(35) + " AQ." + "x".repeat(35)))
                .doesNotContain("bare-value", "AIza", "AQ.");
    }

    @Test
    void preservesMaskProvenanceWhenApplicationAlreadyRedactedTheRequest() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var original = request(0, "Inspect appliance; password=synthetic-only");
        var safe = ConversationRedaction.value(original, ConversationContracts.Submit.class, new com.fasterxml.jackson.databind.ObjectMapper());
        store.begin(owner, id, safe, "hash");
        var message = store.messages(owner, id, 0, 100).items().getFirst();
        assertThat(message.text()).doesNotContain("synthetic-only");
        assertThat(message.redacted()).isTrue();
    }

    @Test
    void interruptedRequestBecomesUnknownWithoutCallingProviderOrExecution() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        store.begin(owner, id, request(0, "Pending"), "hash");
        jdbc.update("update ai_conversation_requests set lease_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
        var planning = mock(DashboardTaskConversation.class);
        var orchestrator = mock(AiAgentOrchestrator.class);
        var contexts = mock(com.voltcanary.aiagent.service.AuthorizedPlanningContext.class);
        when(contexts.scope(actor, "Bearer fixture")).thenReturn(owner);
        var service = new AssistantConversations(store, planning, JsonMapper.builder().findAndAddModules().build(), contexts, new com.voltcanary.aiagent.adapter.JdbcAssistantTraces(jdbc, transactions, store, new com.voltcanary.aiagent.config.TraceProperties(), JsonMapper.builder().findAndAddModules().build()));
        assertThat(service.detail(actor, id, "Bearer fixture").pendingStatus()).isEqualTo("UNKNOWN");
        assertThat(service.detail(actor, id, "Bearer fixture").pendingStatus()).isEqualTo("UNKNOWN");
        assertThat(store.messages(owner, id, 0, 100).items()).hasSize(2);
        verifyNoInteractions(planning, orchestrator);
    }

    @Test
    void recordedProviderFailureAndDuplicateDeliveryInvokePlanningOnlyOnce() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var planning = mock(DashboardTaskConversation.class);
        var orchestrator = mock(AiAgentOrchestrator.class);
        when(planning.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("api_key=must-never-be-stored"));
        var contexts = mock(com.voltcanary.aiagent.service.AuthorizedPlanningContext.class);
        when(contexts.scope(actor, "Bearer fixture")).thenReturn(owner);
        var service = new AssistantConversations(store, planning, JsonMapper.builder().findAndAddModules().build(), contexts, new com.voltcanary.aiagent.adapter.JdbcAssistantTraces(jdbc, transactions, store, new com.voltcanary.aiagent.config.TraceProperties(), JsonMapper.builder().findAndAddModules().build()));
        var request = request(0, "Hello");
        assertThat(service.submit(actor, id, request, "Bearer fixture").pendingStatus()).isEqualTo("FAILED");
        assertThat(service.submit(actor, id, request, "Bearer fixture").pendingStatus()).isEqualTo("FAILED");
        verify(planning, times(1)).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(orchestrator);
        assertThat(store.messages(owner, id, 0, 100).items()).hasSize(2);
        assertThat(store.messages(owner, id, 0, 100).items().getLast().text()).doesNotContain("must-never-be-stored");
    }

    @Test
    void reconstructsExecutionReceiptExactlyOnceAfterLostResponseAndKeepsEarlierReply() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        var request = request(0, "bus22");
        store.begin(owner, id, request, "hash");
        UUID plan = UUID.randomUUID();
        store.finish(owner, id, request.requestId(), plan, null, "Please review bus22", "COMPLETED");
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_reviewed_plans(id,user_id,account_id,version,payload_digest,plan_json,context_json,
                status,result_json,created_at,expires_at,updated_at,retain_until) values(?,?,?,1,'hash','{}','{}','SUCCESS',?,?,?,?,?)
                """, plan, owner.userId(), owner.accountId(), "{\"planId\":\""+plan+"\",\"status\":\"SUCCESS\",\"actions\":[]}",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(86400)));
        store.reconcile(owner, id);
        store.reconcile(owner, id);
        var messages = store.messages(owner, id, 0, 100).items();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).text()).isEqualTo("Please review bus22");
        assertThat(messages.get(2).kind()).isEqualTo("EXECUTION");
        assertThat(messages.get(2).planId()).isEqualTo(plan);
        jdbc.update("update ai_reviewed_plans set status='UNKNOWN',result_json=null where id=?", plan);
        store.reconcile(owner, id);
        store.reconcile(owner, id);
        assertThat(store.messages(owner, id, 0, 100).items()).hasSize(4);
        assertThat(store.messages(owner, id, 0, 100).items().getLast().outcome()).isEqualTo("UNKNOWN");
        jdbc.update("update ai_conversations set expires_at=? where id=?", Timestamp.from(now.minusSeconds(1)), id);
        store.purge();
        assertThat(jdbc.queryForObject("select count(*) from ai_reviewed_plans", Integer.class)).isEqualTo(1);
    }

    @Test
    void retentionPurgesAssociatedRecordsAndSelectionWithoutTouchingAuditPlans() {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        store.begin(owner, id, request(0, "Expire"), "hash");
        jdbc.update("update ai_conversations set expires_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        store.purge();
        assertThat(jdbc.queryForObject("select count(*) from ai_conversation_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ai_conversation_requests", Integer.class)).isZero();
        assertThat(store.selected(owner)).isNull();
        assertThatThrownBy(() -> store.get(owner, id)).isInstanceOf(ResponseStatusException.class);
    }

    private ConversationContracts.Submit request(long revision, String text) {
        return new ConversationContracts.Submit(UUID.randomUUID(), revision, text, Map.of(), null, null, null, null, false, null);
    }

    @Test
    void derivesOnlySuccessfulOwnedCreateReceiptsAcrossStoreRestart() throws Exception {
        UUID id = store.create(owner, UUID.randomUUID()).id();
        UUID device = UUID.randomUUID(), source = UUID.randomUUID(), plan = UUID.randomUUID();
        var request = request(0, "Создай bus22");
        store.begin(owner, id, request, "hash");
        store.finish(owner, id, request.requestId(), plan, null, "Review", "COMPLETED");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        String receipt = mapper.writeValueAsString(new com.voltcanary.aiagent.dto.ExecutePlanResponse(plan, "SUCCESS", List.of(
                new com.voltcanary.aiagent.dto.ActionExecutionResponse(UUID.randomUUID(), "CREATE_DEVICE", "SUCCESS",
                        Map.of("deviceId", device.toString(), "sourceId", source.toString(), "device", Map.of("name", "bus22")), null))));
        var now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into ai_reviewed_plans(id,user_id,account_id,version,payload_digest,plan_json,context_json,
                status,result_json,created_at,expires_at,updated_at,retain_until) values(?,?,?,1,'hash','{}','{}','SUCCESS',?,?,?,?,?)
                """, plan, owner.userId(), owner.accountId(), receipt, now, now, now, now);
        var restarted = new ConversationStore(jdbc, transactions, new ConversationProperties(), mapper);
        assertThat(restarted.resources(owner, id)).singleElement().satisfies(resource -> {
            assertThat(resource.deviceId()).isEqualTo(device);
            assertThat(resource.sourceId()).isEqualTo(source.toString());
            assertThat(resource.reviewId()).isEqualTo(plan);
        });
        for (String status : List.of("READY", "NEEDS_INPUT", "RUNNING", "FAILED", "UNKNOWN")) {
            jdbc.update("update ai_reviewed_plans set status=? where id=?", status, plan);
            assertThat(restarted.resources(owner, id)).isEmpty();
        }
        jdbc.update("update ai_reviewed_plans set status='PARTIAL_SUCCESS' where id=?", plan);
        assertThat(restarted.resources(owner, id)).hasSize(1);
        jdbc.update("update ai_reviewed_plans set result_json=? where id=?", receipt.replace("\"status\":\"SUCCESS\"", "\"status\":\"UNKNOWN\""), plan);
        assertThat(restarted.resources(owner, id)).isEmpty();
        assertThatThrownBy(() -> restarted.resources(new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(UUID.randomUUID(), owner.accountId()), id))
                .isInstanceOf(ResponseStatusException.class);
    }
}
