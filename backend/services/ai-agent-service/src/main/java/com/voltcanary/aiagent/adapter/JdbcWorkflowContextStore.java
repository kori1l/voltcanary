package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.TurnSubmitSelectionItem;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.port.WorkflowContextStore;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
public final class JdbcWorkflowContextStore implements WorkflowContextStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;

    @Override
    public List<List<TurnSubmitSelectionItem>> recentSelections(Scope owner, UUID conversation) {
        return jdbc.query(
            """
            select r.request_json from ai_orchestration_requests r
            join ai_orchestrations p on p.id=r.plan_id
            join ai_conversation_messages m on m.id=r.message_id
            where p.conversation_id=? and p.user_id=? and p.account_id=? and r.request_kind='TURN'
            order by m.sequence desc limit 100
            """,
            (row, index) -> {
                var result = new ArrayList<TurnSubmitSelectionItem>();
                for (var item : validator.parse(row.getString(1)).path("selection")) {
                    result.add(
                        new TurnSubmitSelectionItem(
                            item.path("field").asText(),
                            item.path("ref").asText()
                        )
                    );
                }
                return List.copyOf(result);
            },
            conversation,
            owner.userId(),
            owner.accountId()
        );
    }

    @Override
    public Optional<Prepared> load(UUID plan, UUID request, String step) {
        var rows = jdbc.query(
            "select context_json from ai_orchestration_contexts where plan_id=? and request_id=? and step_id=?",
            (row, index) -> row.getString(1),
            plan,
            request,
            step
        );
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(rows.getFirst(), Prepared.class));
        } catch (java.io.IOException invalid) {
            throw IntentPlans.reject("WORKFLOW_CONTEXT_INVALID");
        }
    }

    @Override
    public void save(UUID plan, UUID request, String step, Prepared context) {
        String json;
        try {
            json = mapper.writeValueAsString(context);
        } catch (java.io.IOException invalid) {
            throw IntentPlans.reject("WORKFLOW_CONTEXT_INVALID");
        }
        int count = jdbc.update(
            "update ai_orchestration_contexts set context_json=? where plan_id=? and request_id=? and step_id=?",
            json,
            plan,
            request,
            step
        );
        if (count == 0) jdbc.update(
            "insert into ai_orchestration_contexts(plan_id,request_id,step_id,context_json) values(?,?,?,?)",
            plan,
            request,
            step,
            json
        );
    }
}
