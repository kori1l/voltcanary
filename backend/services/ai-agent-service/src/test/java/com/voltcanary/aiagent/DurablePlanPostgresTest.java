package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "app.ai.secrets.encryption-key=change_me_32_bytes_minimum_local_dev_key"})
class DurablePlanPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PlanStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.voltcanary.aiagent.conversation.ConversationStore conversations;
    @Autowired com.voltcanary.aiagent.port.AssistantTraces traces;

    @Test
    void conversationMigrationAndPostgresRevisionLocksSurviveReplicaReads() throws Exception {
        var user = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        UUID id = UUID.randomUUID();
        conversations.create(user, id);
        var request = new com.voltcanary.aiagent.conversation.ConversationContracts.Submit(UUID.randomUUID(), 0,
                "bus22 history", Map.of(), null, null, null, null, false, null);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 12; index++) calls.add(() -> conversations.begin(user, id, request, "same-payload").claimed());
        try (var executor = Executors.newFixedThreadPool(6)) {
            int winners = 0;
            for (var answer : executor.invokeAll(calls)) if (answer.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        conversations.finish(user, id, request.requestId(), null, null, "Recorded failure", "FAILED");
        var before = conversations.messages(user, id, 0, 100).items();
        var replica = new com.voltcanary.aiagent.conversation.ConversationStore(jdbc,
                new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())),
                new com.voltcanary.aiagent.config.ConversationProperties(), mapper);
        assertThat(replica.messages(user, id, 0, 100).items()).isEqualTo(before);
        assertThat(replica.selected(user)).isEqualTo(id);
        assertThat(replica.get(user, id).revision()).isEqualTo(2);
        var correlation = traces.input(user, id, request.requestId());
        UUID attempt = UUID.randomUUID();
        traces.append(correlation, attempt, "MODEL_CALL", "STARTED", null, null, Map.of("model", "postgres-fixture"));
        traces.append(correlation, attempt, "MODEL_CALL", "FAILED", "FIXTURE_FAILURE", 1L, Map.of("fieldPaths", List.of("dataRefs")));
        assertThat(traces.read(user, id, request.requestId(), 0, 100).items()).hasSize(2);
    }

    @Test
    void parallelReplicasClaimOnceAndRestartReadsTheSameResultAndStableKey() throws Exception {
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var plan = plan(scope);
        var saved = store.save(plan, new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of()));
        var replica = new PlanStore(jdbc, mapper, new com.voltcanary.aiagent.config.AiTaskProperties());
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            PlanStore worker = index % 2 == 0 ? store : replica;
            calls.add(() -> worker.claim(saved, scope, 1, saved.digest()));
        }
        try (var executor = Executors.newFixedThreadPool(6)) {
            int winners = 0;
            for (var answer : executor.invokeAll(calls)) if (answer.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        var action = plan.actions().getFirst();
        store.actionStarted(saved, scope, action);
        var result = new ActionExecutionResponse(action.id(), action.tool().name(), "SUCCESS", Map.of("deviceId", UUID.randomUUID()), null);
        store.actionFinished(saved, scope, result);
        var execution = new ExecutePlanResponse(plan.id(), "SUCCESS", List.of(result));
        store.finish(saved, scope, execution);
        var recovered = replica.find(plan.id(), scope);
        assertThat(recovered.status()).isEqualTo("SUCCESS");
        assertThat(recovered.execution().actions().getFirst().result()).containsKey("deviceId");
        UUID conversation = conversations.create(scope, UUID.randomUUID()).id();
        var request = new com.voltcanary.aiagent.conversation.ConversationContracts.Submit(UUID.randomUUID(), 0,
                "Create fixture", Map.of(), null, null, null, null, false, null);
        conversations.begin(scope, conversation, request, "fixture");
        conversations.finish(scope, conversation, request.requestId(), plan.id(), null, "Review", "COMPLETED");
        assertThat(conversations.resources(scope, conversation)).singleElement().satisfies(resource -> {
            assertThat(resource.deviceId().toString()).isEqualTo(result.result().get("deviceId").toString());
            assertThat(resource.reviewId()).isEqualTo(plan.id());
            assertThat(resource.actionId()).isEqualTo(action.id());
        });
        assertThat(replica.claim(recovered, scope, 1, saved.digest())).isFalse();
        assertThat(jdbc.queryForObject("select idempotency_key from ai_plan_actions where plan_id=?", String.class, plan.id()))
                .isEqualTo(PlanStore.actionKey(scope, plan.id(), 1, action.id()));
        assertThatThrownBy(() -> store.find(plan.id(), new AuthorizedPlanningContext.Scope(UUID.randomUUID(), scope.accountId())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> store.claim(recovered, scope, 2, saved.digest())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.claim(recovered, scope, 1, "changed")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void continuationInputIsDurableAndScopedAcrossRepositoryInstances() {
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var plan = plan(scope);
        var input = Map.<String,Object>of("_clarification", Map.of("round", 1, "input", Map.of("deviceIds", List.of(UUID.randomUUID().toString())),
                "answers", List.of("Название")));
        store.save(plan, new AuthorizedPlanningContext.Prepared(scope, input, List.of()));
        var replica = new PlanStore(jdbc, mapper, new com.voltcanary.aiagent.config.AiTaskProperties());
        assertThat(replica.context(plan.id(), scope)).isEqualTo(input);
        assertThatThrownBy(() -> replica.context(plan.id(), new AuthorizedPlanningContext.Scope(UUID.randomUUID(), scope.accountId())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiryAndInterruptedMutationNeverResetToAnExecutableReadyState() {
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var first = store.save(plan(scope), new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of()));
        jdbc.update("update ai_reviewed_plans set expires_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(1)), first.plan().id());
        var expired = store.find(first.plan().id(), scope);
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> store.claim(expired, scope, 1, expired.digest())).isInstanceOf(ResponseStatusException.class);
        var second = store.save(plan(scope), new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of()));
        assertThat(store.claim(second, scope, 1, second.digest())).isTrue();
        store.actionStarted(second, scope, second.plan().actions().getFirst());
        jdbc.update("update ai_reviewed_plans set claimed_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(660)), second.plan().id());
        store.maintain();
        var unknown = store.find(second.plan().id(), scope);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(store.actionResults(unknown.plan()).getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(store.claim(unknown, scope, 1, unknown.digest())).isFalse();
        assertThat(store.claimReconciliation(unknown, scope)).isTrue();
        assertThat(store.claimReconciliation(unknown, scope)).isFalse();
        assertThat(jdbc.queryForObject("select idempotency_key from ai_plan_actions where plan_id=?", String.class, second.plan().id()))
                .isEqualTo(PlanStore.actionKey(scope, second.plan().id(), 1, second.plan().actions().getFirst().id()));
    }

    @Test
    void nativeReviewLeaseIsNeverExtendedAndExpiredUnknownInspectionClaimsOnce() throws Exception {
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var original = plan(scope);
        var nativePlan = new AgentPlan(original.id(), original.userId(), original.userMessage(), original.summary(),
                original.explanation(), original.safetyNotes(), List.of(new AgentAction(UUID.randomUUID(),
                AgentTool.CREATE_NATIVE_TABLE, "Create table", "Reviewed table", true, Map.of())), original.createdAt());
        Instant ownerExpiry = Instant.now().plusSeconds(60).with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
        var prepared = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of());
        var saved = store.saveBounded(nativePlan, prepared, ownerExpiry);
        assertThat(saved.expiresAt()).isEqualTo(ownerExpiry.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(store.claimNativeReceiptInspection(saved, scope)).isFalse();
        jdbc.update("update ai_reviewed_plans set status='UNKNOWN',expires_at=? where id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), saved.plan().id());
        var unknown = store.find(saved.plan().id(), scope);
        assertThat(store.claimReconciliation(unknown, scope)).isFalse();
        var otherScope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), scope.accountId());
        assertThat(store.claimNativeReceiptInspection(unknown, otherScope)).isFalse();
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 12; index++) calls.add(() -> store.claimNativeReceiptInspection(unknown, scope));
        try (var executor = Executors.newFixedThreadPool(6)) {
            int winners = 0;
            for (var answer : executor.invokeAll(calls)) if (answer.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.find(saved.plan().id(), scope).expiresAt()).isEqualTo(unknown.expiresAt());
        assertThatThrownBy(() -> store.saveBounded(plan(scope), prepared, Instant.now().minusSeconds(1)))
                .isInstanceOf(ResponseStatusException.class);
        var legacy = store.save(plan(scope), prepared);
        assertThatThrownBy(() -> store.claimNativeReceiptInspection(legacy, scope)).isInstanceOf(IllegalArgumentException.class);
    }

    private AgentPlan plan(AuthorizedPlanningContext.Scope scope) {
        return new AgentPlan(UUID.randomUUID(), scope.userId(), "Создать устройство", "Review", "Explicit review",
                List.of(), List.of(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "Create", "One device", true,
                        Map.of("name", "Fixture", "type", "FRIDGE"))), Instant.now());
    }
}
