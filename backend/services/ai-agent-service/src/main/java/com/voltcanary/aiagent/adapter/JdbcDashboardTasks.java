package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiTaskProperties;
import com.voltcanary.aiagent.domain.DashboardTaskState;
import com.voltcanary.aiagent.domain.DashboardTurn;
import com.voltcanary.aiagent.port.DashboardTasks;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcDashboardTasks implements DashboardTasks {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration retention;
    private final TransactionTemplate transactions;

    public JdbcDashboardTasks(JdbcTemplate jdbc, ObjectMapper mapper, AiTaskProperties properties,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.retention = properties.getRetention().compareTo(Duration.ofDays(30)) < 0
                ? Duration.ofDays(30) : properties.getRetention();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<DashboardTurn> request(UUID userId, UUID accountId, UUID requestId) {
        return jdbc.query("select turn_json from ai_dashboard_turns where user_id=? and account_id=? and request_id=?",
                (row, index) -> read(row.getString(1), DashboardTurn.class), userId, accountId, requestId).stream().findFirst();
    }

    @Override
    public DashboardTaskState find(UUID userId, UUID accountId, UUID taskId, UUID branchId) {
        var state = jdbc.query("select state_json from ai_dashboard_tasks where user_id=? and account_id=? and task_id=? and branch_id=?",
                (row, index) -> read(row.getString(1), DashboardTaskState.class), userId, accountId, taskId, branchId)
                .stream().findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Dashboard task not found"));
        if (!state.expiresAt().isAfter(Instant.now())) throw error(HttpStatus.GONE, "Dashboard task expired");
        return state;
    }

    @Override
    public Optional<DashboardTurn> review(UUID userId, UUID accountId, UUID reviewId) {
        return jdbc.query("select turn_json from ai_dashboard_turns where user_id=? and account_id=? and review_id=?",
                (row, index) -> read(row.getString(1), DashboardTurn.class), userId, accountId, reviewId).stream().findFirst();
    }

    @Override
    @Transactional
    public DashboardTaskState accept(DashboardTaskState state, DashboardTurn turn, Long expectedRevision, boolean newBranch) {
        if (expectedRevision != null && expectedRevision != state.revision()) {
            throw error(HttpStatus.CONFLICT, "Dashboard task revision changed");
        }
        if (!state.expiresAt().isAfter(Instant.now())) throw error(HttpStatus.GONE, "Dashboard task expired");
        if (state.activeTurnId() != null) throw error(HttpStatus.CONFLICT, "Dashboard turn in progress");
        DashboardTaskState accepted;
        try {
            accepted = state.begin(turn.turnId(), turn.message());
        } catch (IllegalArgumentException failure) {
            throw error(HttpStatus.UNPROCESSABLE_CONTENT, "Split work after thirty continuations");
        }
        try {
            if (newBranch) {
                jdbc.update("""
                        insert into ai_dashboard_tasks(branch_id,task_id,user_id,account_id,revision,state_json,
                        active_turn_id,expires_at,retain_until,updated_at) values(?,?,?,?,?,?,null,?,?,?)
                        """, state.branchId(), state.taskId(), state.userId(), state.accountId(), state.revision(), json(state),
                        time(state.expiresAt()), time(state.expiresAt().plus(retention)), time(Instant.now()));
            }
            int changed = jdbc.update("""
                    update ai_dashboard_tasks set revision=?,state_json=?,active_turn_id=?,updated_at=?
                    where branch_id=? and user_id=? and account_id=? and revision=? and active_turn_id is null
                    """, accepted.revision(), json(accepted), turn.turnId(), time(Instant.now()), state.branchId(),
                    state.userId(), state.accountId(), state.revision());
            if (changed != 1) throw error(HttpStatus.CONFLICT, "Dashboard task revision changed");
            jdbc.update("""
                    insert into ai_dashboard_turns(turn_id,request_id,user_id,account_id,branch_id,payload_hash,
                    revision,status,review_id,turn_json,lease_until) values(?,?,?,?,?,?,?,'PENDING',null,?,?)
                    """, turn.turnId(), turn.requestId(), turn.userId(), turn.accountId(), turn.branchId(),
                    turn.payloadHash(), accepted.revision(), json(turn), time(turn.leaseUntil()));
            return accepted;
        } catch (DuplicateKeyException duplicate) {
            throw error(HttpStatus.CONFLICT, "Dashboard request already accepted");
        }
    }

    @Override
    @Transactional
    public void checkpoint(DashboardTaskState state) {
        int changed = jdbc.update("""
                update ai_dashboard_tasks set state_json=?,updated_at=? where branch_id=? and user_id=?
                and account_id=? and revision=? and active_turn_id=?
                """, json(state), time(Instant.now()), state.branchId(), state.userId(), state.accountId(),
                state.revision(), state.activeTurnId());
        if (changed != 1) throw error(HttpStatus.CONFLICT, "Dashboard turn is no longer active");
    }

    @Override
    @Transactional
    public void finish(DashboardTaskState state, DashboardTurn turn) {
        int changed = jdbc.update("""
                update ai_dashboard_tasks set state_json=?,active_turn_id=null,updated_at=?
                where branch_id=? and user_id=? and account_id=? and revision=? and active_turn_id=?
                """, json(state), time(Instant.now()), state.branchId(), state.userId(), state.accountId(),
                state.revision(), turn.turnId());
        if (changed != 1) throw error(HttpStatus.CONFLICT, "Dashboard turn is no longer active");
        jdbc.update("update ai_dashboard_turns set status=?,review_id=?,turn_json=? where turn_id=? and status='PENDING'",
                turn.status(), turn.reviewId(), json(turn), turn.turnId());
    }

    @Scheduled(fixedDelayString = "${app.ai.tasks.cleanup-delay:60000}", initialDelayString = "${app.ai.tasks.cleanup-delay:60000}")
    public void recoverInterrupted() {
        var turns = jdbc.query("select turn_json from ai_dashboard_turns where status='PENDING' and lease_until<?",
                (row, index) -> read(row.getString(1), DashboardTurn.class), time(Instant.now()));
        for (var turn : turns) {
            transactions.executeWithoutResult(transaction -> {
                var values = jdbc.query("select state_json from ai_dashboard_tasks where branch_id=? and active_turn_id=? for update",
                        (row, index) -> read(row.getString(1), DashboardTaskState.class), turn.branchId(), turn.turnId());
                if (values.isEmpty()) return;
                var failed = values.getFirst().finish(null, false, "INTERRUPTED");
                finish(failed, new DashboardTurn(turn.turnId(), turn.requestId(), turn.taskId(), turn.branchId(),
                        turn.userId(), turn.accountId(), turn.payloadHash(), turn.baseReviewId(), turn.revision(),
                        turn.message(), "FAILED", "INTERRUPTED", null, turn.leaseUntil(), failed));
            });
        }
        jdbc.update("delete from ai_dashboard_tasks where retain_until<?", time(Instant.now()));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode dashboard task state", failure); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot decode dashboard task state", failure); }
    }

    private Timestamp time(Instant value) { return Timestamp.from(value); }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
