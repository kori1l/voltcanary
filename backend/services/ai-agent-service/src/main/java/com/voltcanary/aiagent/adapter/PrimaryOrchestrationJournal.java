package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.TurnSubmit;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owner-scoped mappings; saved reviews and transcript remain the only user-visible history. */
@RequiredArgsConstructor
public final class PrimaryOrchestrationJournal {
    public record Turn(UUID planId, TurnSubmit request, Long clientRevision) { }
    public record Binding(UUID planId, String stepId) { }
    private final JdbcTemplate jdbc;
    private final IntentPlanStore plans;
    private final ObjectMapper mapper;

    public Optional<IntentPlans.State> current(Scope owner, UUID conversation) {
        var rows = jdbc.query("select id from ai_orchestrations where conversation_id=? and user_id=? and account_id=? order by updated_at desc limit 1",
                (row, index) -> row.getObject(1, UUID.class), conversation, owner.userId(), owner.accountId());
        return rows.isEmpty() ? Optional.empty() : Optional.of(plans.read(owner, rows.getFirst()));
    }
    public Optional<Turn> turn(Scope owner, UUID conversation, UUID request) {
        var rows = jdbc.query("select r.plan_id,r.client_revision,r.request_kind from ai_orchestration_requests r join ai_orchestrations p on p.id=r.plan_id where r.conversation_id=? and r.request_id=? and p.user_id=? and p.account_id=?",
                (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getObject(2, Long.class), row.getString(3)},
                conversation, request, owner.userId(), owner.accountId());
        if (rows.isEmpty()) return Optional.empty();
        if (!"TURN".equals(rows.getFirst()[2])) throw IntentPlans.conflict("REQUEST_ID_CONFLICT");
        UUID plan = (UUID) rows.getFirst()[0];
        try {
            return Optional.of(new Turn(plan, mapper.readValue(plans.request(owner, plan, request), TurnSubmit.class), (Long) rows.getFirst()[1]));
        } catch (java.io.IOException invalid) { throw IntentPlans.reject("SAVED_TURN_INVALID"); }
    }
    public Optional<Binding> review(Scope owner, UUID review) {
        var rows = jdbc.query("select b.plan_id,b.step_id from ai_orchestration_review_bindings b join ai_orchestrations p on p.id=b.plan_id where b.review_id=? and p.user_id=? and p.account_id=?",
                (row, index) -> new Binding(row.getObject(1, UUID.class), row.getString(2)), review, owner.userId(), owner.accountId());
        return rows.stream().findFirst();
    }
    public void bind(UUID review, UUID plan, String step) {
        jdbc.update("insert into ai_orchestration_review_bindings(review_id,plan_id,step_id) values(?,?,?)", review, plan, step);
    }
    public java.util.List<UUID> history(Scope owner, UUID plan) {
        plans.read(owner, plan);
        return jdbc.query("select b.review_id from ai_orchestration_review_bindings b join ai_reviewed_plans r on r.id=b.review_id where b.plan_id=? and r.user_id=? and r.account_id=? order by r.created_at limit 30",
                (row, index) -> row.getObject(1, UUID.class), plan, owner.userId(), owner.accountId());
    }
}
