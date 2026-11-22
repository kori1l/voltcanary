package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.JdbcDashboardTasks;
import com.voltcanary.aiagent.config.AiTaskProperties;
import com.voltcanary.aiagent.domain.DashboardTaskState;
import com.voltcanary.aiagent.domain.DashboardTurn;
import com.voltcanary.aiagent.port.DashboardTasks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.ai.secrets.encryption-key=change_me_32_bytes_minimum_local_dev_key",
    }
)
@org.junit.jupiter.api.Tag("docker")
class DashboardWorkingStatePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DashboardTasks tasks;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void nameAndOriginalRequestSurviveFailureAndAReplacementProcess() {
        var state = state();
        var turn = turn(state, UUID.randomUUID(), Instant.now().plusSeconds(900));
        var accepted = tasks.accept(state, turn, 0L, true);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var named = accepted.confirmName(
            "Мой любимый трамвай",
            DashboardTaskState.Provenance.USER_EXPLICIT
        );
        tasks.checkpoint(named);
        var failed = named.finish(null, false, "PROVIDER_FAILURE");
        tasks.finish(failed, completed(turn, failed));
        var replica = new JdbcDashboardTasks(jdbc, mapper, new AiTaskProperties(), transactions);
        var restored = replica.find(
            state.userId(),
            state.accountId(),
            state.taskId(),
            state.branchId()
        );
        assertThat(restored.name().value()).isEqualTo("Мой любимый трамвай");
        assertThat(restored.originalRequest()).isEqualTo(state.originalRequest());
        assertThat(restored.selection().origin()).isEqualTo(state.selection().origin()).isNotNull();
        assertThat(restored.pending()).hasSize(1);
        assertThat(restored.pending().getFirst().disposition()).isEqualTo(
            DashboardTaskState.Disposition.FAILED
        );
        assertThat(restored.lastAcceptedReviewId()).isNull();
        assertThat(
            replica
                .request(state.userId(), state.accountId(), turn.requestId())
                .orElseThrow()
                .outcome()
        ).isEqualTo(restored);
    }

    @Test
    void concurrentTurnsCannotOverwriteAnAcceptedRevision() throws Exception {
        var initial = state();
        var first = turn(initial, UUID.randomUUID(), Instant.now().plusSeconds(900));
        var accepted = tasks.accept(initial, first, 0L, true);
        var base = accepted.finish(null, false, null);
        tasks.finish(base, completed(first, base));
        List<Callable<Boolean>> contenders = new ArrayList<>();
        for (int index = 0; index < 8; index++) contenders.add(() -> {
            try {
                tasks.accept(
                    base,
                    turn(base, UUID.randomUUID(), Instant.now().plusSeconds(900)),
                    1L,
                    false
                );
                return true;
            } catch (ResponseStatusException conflict) {
                assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                return false;
            }
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            int winners = 0;
            for (var result : executor.invokeAll(contenders)) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(
            tasks.find(base.userId(), base.accountId(), base.taskId(), base.branchId()).revision()
        ).isEqualTo(2);
    }

    @Test
    void duplicateRequestRollsBackItsNewBranchAndForeignScopesCannotReadIt() {
        var first = state();
        UUID request = UUID.randomUUID();
        tasks.accept(first, turn(first, request, Instant.now().plusSeconds(900)), 0L, true);
        var duplicate = new DashboardTaskState(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            first.userId(),
            first.accountId(),
            0,
            "Другой запрос",
            null,
            first.selection(),
            null,
            null,
            null,
            null,
            "NEW",
            null,
            first.expiresAt(),
            List.of()
        );
        assertThatThrownBy(() ->
            tasks.accept(
                duplicate,
                turn(duplicate, request, Instant.now().plusSeconds(900)),
                0L,
                true
            )
        ).isInstanceOf(ResponseStatusException.class);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_dashboard_tasks where branch_id=?",
                Integer.class,
                duplicate.branchId()
            )
        ).isZero();
        assertThat(tasks.request(UUID.randomUUID(), first.accountId(), request)).isEmpty();
        assertThat(tasks.request(first.userId(), UUID.randomUUID(), request)).isEmpty();
        assertThatThrownBy(() ->
            tasks.find(UUID.randomUUID(), first.accountId(), first.taskId(), first.branchId())
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(404)
        );
    }

    @Test
    void interruptedTurnRetainsFactsAndCannotLaterOverwriteItsRecoveredState() {
        var initial = state();
        var turn = turn(initial, UUID.randomUUID(), Instant.now().minusSeconds(10));
        var accepted = tasks
            .accept(initial, turn, 0L, true)
            .confirmName("Ночной автобус", DashboardTaskState.Provenance.USER_EXPLICIT);
        tasks.checkpoint(accepted);
        ((JdbcDashboardTasks) tasks).recoverInterrupted();
        var recovered = tasks.find(
            initial.userId(),
            initial.accountId(),
            initial.taskId(),
            initial.branchId()
        );
        assertThat(recovered.failureCategory()).isEqualTo("INTERRUPTED");
        assertThat(recovered.name().value()).isEqualTo("Ночной автобус");
        assertThatThrownBy(() -> tasks.checkpoint(accepted)).isInstanceOf(
            ResponseStatusException.class
        );
        assertThat(recovered.pending().getFirst().disposition()).isEqualTo(
            DashboardTaskState.Disposition.FAILED
        );
    }

    @Test
    void expiryAndRetentionDoNotExposeOrOrphanTurnRecords() throws Exception {
        var initial = state();
        var turn = turn(initial, UUID.randomUUID(), Instant.now().plusSeconds(900));
        var accepted = tasks.accept(initial, turn, 0L, true);
        var done = accepted.finish(null, false, null);
        tasks.finish(done, completed(turn, done));
        var expired = new DashboardTaskState(
            1,
            done.taskId(),
            done.branchId(),
            done.userId(),
            done.accountId(),
            done.revision(),
            done.originalRequest(),
            done.name(),
            done.selection(),
            done.providerId(),
            done.modelConfigId(),
            done.lastAcceptedReviewId(),
            null,
            done.status(),
            null,
            Instant.now().minusSeconds(10),
            done.pending()
        );
        jdbc.update(
            "update ai_dashboard_tasks set state_json=? where branch_id=?",
            mapper.writeValueAsString(expired),
            done.branchId()
        );
        assertThatThrownBy(() ->
            tasks.find(done.userId(), done.accountId(), done.taskId(), done.branchId())
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(410)
        );
        jdbc.update(
            "update ai_dashboard_tasks set retain_until=? where branch_id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
            done.branchId()
        );
        ((JdbcDashboardTasks) tasks).recoverInterrupted();
        assertThat(tasks.request(done.userId(), done.accountId(), turn.requestId())).isEmpty();
    }

    @Test
    void aRestoredBranchKeepsItsOwnFactsAndLeavesNewerWorkUntouched() {
        var initial = state();
        var first = turn(initial, UUID.randomUUID(), Instant.now().plusSeconds(900));
        var snapshot = tasks
            .accept(initial, first, 0L, true)
            .confirmName("Первое имя", DashboardTaskState.Provenance.USER_EXPLICIT)
            .finish(null, false, null);
        tasks.finish(snapshot, completed(first, snapshot));
        var second = turn(snapshot, UUID.randomUUID(), Instant.now().plusSeconds(900));
        var newer = tasks
            .accept(snapshot, second, 1L, false)
            .confirmName("Новое имя", DashboardTaskState.Provenance.USER_EXPLICIT)
            .finish(null, false, "PREPARATION_FAILED");
        tasks.finish(newer, completed(second, newer));
        var branch = new DashboardTaskState(
            1,
            snapshot.taskId(),
            UUID.randomUUID(),
            snapshot.userId(),
            snapshot.accountId(),
            snapshot.revision(),
            snapshot.originalRequest(),
            snapshot.name(),
            snapshot.selection(),
            null,
            null,
            null,
            null,
            "NEW",
            null,
            snapshot.expiresAt(),
            snapshot.pending()
        );
        var branched = tasks.accept(
            branch,
            turn(branch, UUID.randomUUID(), Instant.now().plusSeconds(900)),
            1L,
            true
        );
        assertThat(branched.name().value()).isEqualTo("Первое имя");
        assertThat(
            tasks
                .find(newer.userId(), newer.accountId(), newer.taskId(), newer.branchId())
                .name()
                .value()
        ).isEqualTo("Новое имя");
        assertThat(branched.pending()).hasSize(2);
        assertThat(newer.pending()).hasSize(2);
    }

    private DashboardTaskState state() {
        UUID task = UUID.randomUUID();
        return new DashboardTaskState(
            1,
            task,
            task,
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "Создай дашборд: карта, скорость, двери",
            null,
            new DashboardTaskState.Selection(
                List.of(UUID.randomUUID()),
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                new DashboardTaskState.SelectionOrigin(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()
                )
            ),
            null,
            null,
            null,
            null,
            "NEW",
            null,
            Instant.now().plusSeconds(3600),
            List.of()
        );
    }

    private DashboardTurn turn(DashboardTaskState state, UUID request, Instant lease) {
        return new DashboardTurn(
            UUID.randomUUID(),
            request,
            state.taskId(),
            state.branchId(),
            state.userId(),
            state.accountId(),
            "fixture-hash",
            null,
            state.revision() + 1,
            "Назови Мой любимый трамвай",
            "PENDING",
            null,
            null,
            lease,
            null
        );
    }

    private DashboardTurn completed(DashboardTurn turn, DashboardTaskState state) {
        return new DashboardTurn(
            turn.turnId(),
            turn.requestId(),
            turn.taskId(),
            turn.branchId(),
            turn.userId(),
            turn.accountId(),
            turn.payloadHash(),
            turn.baseReviewId(),
            turn.revision(),
            turn.message(),
            "COMPLETED",
            state.failureCategory(),
            null,
            turn.leaseUntil(),
            state
        );
    }
}
