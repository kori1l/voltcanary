package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PlanStore {
    public record Stored(AgentPlan plan, UUID accountId, int version, String digest, Instant expiresAt,
            String status, ExecutePlanResponse execution, List<ResourceRef> refs) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration expiry;
    private final Duration retention;

    public PlanStore(JdbcTemplate jdbc, ObjectMapper mapper, com.voltcanary.aiagent.config.AiTaskProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.expiry = properties.getExpiry();
        Duration retention = properties.getRetention();
        this.retention = retention.compareTo(Duration.ofDays(30)) < 0 ? Duration.ofDays(30) : retention;
    }

    @Transactional
    public Stored save(AgentPlan plan, AuthorizedPlanningContext.Prepared context) {
        return saveChecked(plan, context, null);
    }

    /** A shorter owning review lease can never be renewed by the assistant's longer review TTL. */
    @Transactional
    public Stored saveBounded(AgentPlan plan, AuthorizedPlanningContext.Prepared context, Instant ownerExpiresAt) {
        return saveChecked(plan, context, java.util.Objects.requireNonNull(ownerExpiresAt));
    }

    private Stored saveChecked(AgentPlan plan, AuthorizedPlanningContext.Prepared context, Instant ownerExpiresAt) {
        Instant now = Instant.now();
        String digest = digest(plan.actions());
        Instant expires = now.plus(expiry);
        if (ownerExpiresAt != null && ownerExpiresAt.isBefore(expires)) expires = ownerExpiresAt;
        if (ownerExpiresAt != null) expires = expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!expires.isAfter(now)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Owning review expired; prepare again");
        String status = plan.actions().isEmpty() ? "NEEDS_INPUT" : "READY";
        jdbc.update("""
                insert into ai_reviewed_plans(id,user_id,account_id,version,payload_digest,plan_json,context_json,
                status,created_at,expires_at,updated_at,retain_until) values(?,?,?,1,?,?,?,?,?,?,?,?)
                """, plan.id(), plan.userId(), context.scope().accountId(), digest, json(plan), json(context.context()),
                status, time(now), time(expires), time(now), time(expires.plus(retention)));
        for (ResourceRef ref : context.refs()) {
            jdbc.update("insert into ai_plan_references(plan_id,ref,kind,resource_id,summary_json) values(?,?,?,?,?)",
                    plan.id(), ref.ref(), ref.kind(), ref.resourceId(), json(ref.summary()));
        }
        for (int index = 0; index < plan.actions().size(); index++) {
            AgentAction action = plan.actions().get(index);
            jdbc.update("""
                    insert into ai_plan_actions(plan_id,action_id,action_order,idempotency_key,status,updated_at)
                    values(?,?,?,?,'READY',?)
                    """, plan.id(), action.id(), index, actionKey(context.scope(), plan.id(), 1, action.id()), time(now));
        }
        audit(plan, context.scope(), null, "PREPARED");
        return find(plan.id(), context.scope());
    }

    public Stored find(UUID id, Scope scope) {
        List<Stored> result = jdbc.query("select * from ai_reviewed_plans where id=? and user_id=? and account_id=?",
                (row, index) -> new Stored(parse(row.getString("plan_json"), AgentPlan.class), scope.accountId(),
                        row.getInt("version"), row.getString("payload_digest"), row.getTimestamp("expires_at").toInstant(),
                        row.getString("status"), row.getString("result_json") == null ? null
                                : parse(row.getString("result_json"), ExecutePlanResponse.class), List.of()),
                id, scope.userId(), scope.accountId());
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        Stored saved = result.getFirst();
        List<ResourceRef> refs = jdbc.query("select * from ai_plan_references where plan_id=? order by ref", (row, index) ->
                new ResourceRef(row.getString("ref"), row.getString("kind"), row.getString("resource_id"),
                        parseMap(row.getString("summary_json"))), id);
        String status = !saved.expiresAt().isAfter(Instant.now()) && List.of("READY", "NEEDS_INPUT").contains(saved.status())
                ? "EXPIRED" : saved.status();
        return new Stored(saved.plan(), saved.accountId(), saved.version(), saved.digest(), saved.expiresAt(), status, saved.execution(), refs);
    }

    public Map<String, Object> context(UUID id, Scope scope) {
        var values = jdbc.query("select context_json from ai_reviewed_plans where id=? and user_id=? and account_id=?",
                (row, index) -> parseMap(row.getString("context_json")), id, scope.userId(), scope.accountId());
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        return values.getFirst();
    }

    @Transactional
    public boolean claim(Stored saved, Scope scope, Integer version, String digest) {
        if ((version != null && version != saved.version()) || (digest != null && !digest.equals(saved.digest()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reviewed plan version changed");
        }
        if ("EXPIRED".equals(saved.status())) throw new ResponseStatusException(HttpStatus.GONE, "Plan expired; prepare again");
        if ("NEEDS_INPUT".equals(saved.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Clarification required");
        int claimed = jdbc.update("""
                update ai_reviewed_plans set status='RUNNING',claimed_at=?,updated_at=?
                where id=? and user_id=? and account_id=? and version=? and payload_digest=?
                and status='READY' and expires_at>?
                """, time(Instant.now()), time(Instant.now()), saved.plan().id(), scope.userId(), scope.accountId(),
                saved.version(), saved.digest(), time(Instant.now()));
        if (claimed == 1) audit(saved.plan(), scope, null, "EXECUTION_CLAIMED");
        return claimed == 1;
    }

    @Transactional
    public void actionStarted(Stored saved, Scope scope, AgentAction action) {
        jdbc.update("update ai_plan_actions set status='RUNNING',updated_at=? where plan_id=? and action_id=? and status='READY'",
                time(Instant.now()), saved.plan().id(), action.id());
        audit(saved.plan(), scope, action.id(), "ACTION_STARTED");
    }

    @Transactional
    public boolean claimReconciliation(Stored saved, Scope scope) {
        int claimed = jdbc.update("""
                update ai_reviewed_plans set status='RUNNING',result_json=null,claimed_at=?,updated_at=?
                where id=? and user_id=? and account_id=? and version=? and payload_digest=?
                and status='UNKNOWN' and expires_at>?
                """, time(Instant.now()), time(Instant.now()), saved.plan().id(), scope.userId(), scope.accountId(),
                saved.version(), saved.digest(), time(Instant.now()));
        if (claimed == 1) audit(saved.plan(), scope, null, "RECONCILIATION_CLAIMED");
        return claimed == 1;
    }

    /** Private journal claim only. The executor must use native receipt/resource GETs, with no owner POST. */
    @Transactional
    public boolean claimNativeReceiptInspection(Stored saved, Scope scope) {
        if (saved.plan().actions().size() != 1 || !java.util.Set.of(com.voltcanary.aiagent.model.AgentTool.CREATE_NATIVE_TABLE, com.voltcanary.aiagent.model.AgentTool.UPDATE_VEHICLE_MONITORING).contains(saved.plan().actions().getFirst().tool())) {
            throw new IllegalArgumentException("Native receipt inspection required");
        }
        int claimed = jdbc.update("""
                update ai_reviewed_plans set status='RUNNING',result_json=null,claimed_at=?,updated_at=?
                where id=? and user_id=? and account_id=? and version=? and payload_digest=? and status='UNKNOWN'
                """, time(Instant.now()), time(Instant.now()), saved.plan().id(), scope.userId(), scope.accountId(), saved.version(), saved.digest());
        if (claimed == 1) audit(saved.plan(), scope, null, "RECEIPT_INSPECTION_CLAIMED");
        return claimed == 1;
    }

    @Transactional
    public void actionFinished(Stored saved, Scope scope, ActionExecutionResponse result) {
        jdbc.update("update ai_plan_actions set status=?,result_json=?,updated_at=? where plan_id=? and action_id=?",
                result.status(), json(result), time(Instant.now()), saved.plan().id(), result.actionId());
        audit(saved.plan(), scope, result.actionId(), "ACTION_" + result.status());
    }

    @Transactional
    public void finish(Stored saved, Scope scope, ExecutePlanResponse result) {
        jdbc.update("update ai_reviewed_plans set status=?,result_json=?,updated_at=? where id=? and user_id=? and account_id=? and status='RUNNING'",
                result.status(), json(result), time(Instant.now()), saved.plan().id(), scope.userId(), scope.accountId());
        audit(saved.plan(), scope, null, "EXECUTION_" + result.status());
    }

    public List<ActionExecutionResponse> actionResults(AgentPlan plan) {
        return jdbc.query("select action_id,status,result_json from ai_plan_actions where plan_id=? order by action_order",
                (row, index) -> {
                    String result = row.getString("result_json");
                    if (result != null) return parse(result, ActionExecutionResponse.class);
                    UUID id = row.getObject("action_id", UUID.class);
                    String tool = plan.actions().stream().filter(action -> action.id().equals(id))
                            .map(action -> action.tool().name()).findFirst().orElse("UNKNOWN");
                    return new ActionExecutionResponse(id, tool, row.getString("status"), Map.of(), null);
                }, plan.id());
    }

    @Scheduled(fixedDelayString = "${app.ai.tasks.cleanup-delay:60000}", initialDelayString = "${app.ai.tasks.cleanup-delay:60000}")
    @Transactional
    public void maintain() {
        Instant now = Instant.now();
        // Never reset an interrupted mutation to READY: the domain may already have committed it.
        jdbc.update("update ai_reviewed_plans set status='UNKNOWN',updated_at=? where status='RUNNING' and claimed_at<?",
                time(now), time(now.minus(Duration.ofMinutes(10))));
        jdbc.update("update ai_plan_actions set status='UNKNOWN',updated_at=? where status='RUNNING' and plan_id in (select id from ai_reviewed_plans where status='UNKNOWN')",
                time(now));
        jdbc.update("delete from ai_reviewed_plans where retain_until<? and status<>'RUNNING'", time(now));
    }

    public static String actionKey(Scope scope, UUID plan, int version, UUID action) {
        return "ai-" + scope.userId() + "-" + scope.accountId() + "-" + plan + "-" + version + "-" + action;
    }

    public void recordConfirmation(Stored saved, Scope scope) {
        audit(saved.plan(), scope, null, "REVIEW_CONFIRMED");
    }

    /** Revoke execution eligibility without changing the immutable reviewed payload or completed receipts. */
    @Transactional
    public void invalidate(UUID id, Scope scope) {
        find(id, scope);
        int changed = jdbc.update("update ai_reviewed_plans set status='SUPERSEDED',updated_at=? where id=? and user_id=? and account_id=? and status='READY'",
                time(Instant.now()), id, scope.userId(), scope.accountId());
        if (changed == 0 && List.of("RUNNING", "UNKNOWN", "SUCCESS", "PARTIAL_SUCCESS").contains(find(id, scope).status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review execution already started");
        }
    }

    private void audit(AgentPlan plan, Scope scope, UUID action, String event) {
        jdbc.update("insert into ai_plan_audit(id,plan_id,user_id,account_id,version,action_id,event_type,model_config_id,created_at) values(?,?,?,?,1,?,?,?,?)",
                UUID.randomUUID(), plan.id(), scope.userId(), scope.accountId(), action, event, plan.modelConfigId(), time(Instant.now()));
    }

    private String digest(Object value) {
        try {
            byte[] canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception failure) { throw new IllegalStateException("Cannot encode reviewed plan", failure); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode plan state", failure); }
    }

    private <T> T parse(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read plan state", failure); }
    }

    private Map<String, Object> parseMap(String value) {
        try { return mapper.readValue(value, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read reference state", failure); }
    }

    private static Timestamp time(Instant value) { return Timestamp.from(value); }
}
