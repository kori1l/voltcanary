package com.voltcanary.aiagent;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CollaborationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private CollaborationStoreTest.Fixture fixture() throws Exception {
        String schema = "collaboration_" + UUID.randomUUID().toString().replace("-", "");
        var root = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new JdbcTemplate(root).execute("create schema " + schema);
        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        return CollaborationStoreTest.fixture(new IntentScenarioSupport(new DriverManagerDataSource(
                postgres.getJdbcUrl() + separator + "currentSchema=" + schema, postgres.getUsername(), postgres.getPassword())));
    }

    @Test
    void restartAndReplayKeepOneSnapshotAndOneJournalEntry() throws Exception {
        CollaborationStoreTest.verifyRestartAndReplay(fixture());
    }

    @Test
    void journalFailureRollsBackSnapshot() throws Exception {
        CollaborationStoreTest.verifyRollback(fixture());
    }

    @Test
    void requirementQuestionAndReviewInvalidationShareOneCommit() throws Exception {
        CollaborationStoreTest.verifyAtomicRequirements(fixture());
    }

    @Test
    void rootTurnAndOwnerFencesAreRequired() throws Exception {
        CollaborationStoreTest.verifyFences(fixture());
    }

    @Test
    void nativeSpecialistSubgraphSharesPostgresLedgerAndReplays() throws Exception {
        ConsultationWorkerTest.verify(fixture(), false);
    }

    @Test
    void unknownSpecialistExchangeKeepsPostgresReservation() throws Exception {
        ConsultationWorkerTest.verify(fixture(), true);
    }

    @Test
    void duplicateWorkerCannotCompleteTheFirstWorkersReservation() throws Exception {
        ConsultationWorkerTest.verify(fixture(), false, true);
    }

    @Test
    void leaseIsCheckedAfterWaitingForConversationLock() throws Exception {
        var fixture = fixture();
        var f = fixture.f();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var expiry = f.jdbc.queryForObject("select clock_timestamp() + interval '1 second'", java.sql.Timestamp.class);
        f.jdbc.update("update ai_orchestration_requests set lease_until=? where plan_id=?", expiry, fixture.turn().planId());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> f.transactions.executeWithoutResult(status -> {
                f.jdbc.queryForObject("select id from ai_conversations where id=? for update", UUID.class, fixture.turn().conversationId());
                locked.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("FIXTURE_TIMEOUT");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var waiting = pool.submit(() -> fixture.store().update(fixture.turn(), 0, "waiting", "a".repeat(64),
                    (before, now) -> CollaborationStoreTest.propose(fixture, before)));
            try {
                Thread.sleep(1500);
                assertThat(waiting.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> waiting.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.voltcanary.aiagent.orchestration.collaboration.Requirements.Rejected.class)
                    .cause().hasMessage("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
            assertThat(f.jdbc.queryForObject("select count(*) from ai_orchestration_collaboration", Long.class)).isZero();
        } finally {
            release.countDown();
        }
    }
}
