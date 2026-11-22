package com.voltcanary.aiagent.adapter;

import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.conflict;
import static com.voltcanary.aiagent.orchestration.collaboration.Requirements.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationPolicy;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same conversation -> plan lock order as the root store; time is sampled after locks from the database. */
public final class JdbcCollaborationStore implements CollaborationStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public JdbcCollaborationStore(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        ObjectMapper mapper
    ) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(10);
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(10);
        this.mapper = mapper
            .copy()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.postgres = this.jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
        );
    }

    @Override
    public State read(Scope owner, UUID planId) {
        return transactions.execute(status -> readInTransaction(owner, planId));
    }

    State readInTransaction(Scope owner, UUID planId) {
        var roots = jdbc.query(
            """
            select p.state_json from ai_orchestrations p join ai_conversations c on c.id=p.conversation_id
            where p.id=? and p.user_id=? and p.account_id=? and c.user_id=? and c.account_id=?
            and p.expires_at>? and c.expires_at>?
            """,
            (row, index) -> row.getString(1),
            planId,
            owner.userId(),
            owner.accountId(),
            owner.userId(),
            owner.accountId(),
            time(now()),
            time(now())
        );
        if (roots.isEmpty()) throw new Requirements.Rejected(404, "PLAN_UNAVAILABLE");
        var states = jdbc.query(
            "select state_json from ai_orchestration_collaboration where plan_id=?",
            (row, index) -> parse(row.getString(1), State.class),
            planId
        );
        if (!states.isEmpty()) return states.getFirst();
        var root = parse(roots.getFirst(), IntentPlans.State.class);
        return initial(root, now());
    }

    @Override
    public State update(
        IntentPlans.Token turn,
        long expectedRevision,
        String requestKey,
        String requestDigest,
        Mutation mutation
    ) {
        if (
            requestKey != null &&
            (requestKey.startsWith("requirements:") || requestKey.startsWith("cancel:"))
        ) {
            throw invalid("RESERVED_COLLABORATION_REQUEST_KEY");
        }
        return transactions.execute(status ->
            updateInTransaction(
                turn,
                expectedRevision,
                requestKey,
                requestDigest,
                mutation,
                (before, next) -> {}
            )
        );
    }

    State updateInTransaction(
        IntentPlans.Token turn,
        long expectedRevision,
        String requestKey,
        String requestDigest,
        Mutation mutation,
        BiConsumer<State, State> persistRoot
    ) {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) throw new IllegalStateException("COLLABORATION_TRANSACTION_REQUIRED");
        if (
            requestKey == null ||
            requestKey.isBlank() ||
            requestKey.length() > 160 ||
            requestDigest == null ||
            !requestDigest.matches("[0-9a-f]{64}")
        ) throw invalid("INVALID_COLLABORATION_REQUEST");
        var visible = jdbc.query(
            "select id from ai_conversations where id=? and user_id=? and account_id=? for update",
            (row, index) -> row.getObject(1, UUID.class),
            turn.conversationId(),
            turn.owner().userId(),
            turn.owner().accountId()
        );
        if (visible.isEmpty()) throw new Requirements.Rejected(404, "PLAN_UNAVAILABLE");
        var roots = jdbc.query(
            """
            select state_json,revision,active_request_id from ai_orchestrations
            where id=? and conversation_id=? and user_id=? and account_id=? for update
            """,
            (row, index) ->
                new Root(row.getString(1), row.getLong(2), row.getObject(3, UUID.class)),
            turn.planId(),
            turn.conversationId(),
            turn.owner().userId(),
            turn.owner().accountId()
        );
        if (roots.isEmpty()) throw new Requirements.Rejected(404, "PLAN_UNAVAILABLE");
        Instant now = now();
        var root = roots.getFirst();
        var rootState = parse(root.json(), IntentPlans.State.class);
        Long live = jdbc.queryForObject(
            """
            select count(*) from ai_orchestrations p join ai_conversations c on c.id=p.conversation_id
            join ai_orchestration_requests r on r.plan_id=p.id
            join ai_conversation_requests q on q.conversation_id=r.conversation_id and q.request_id=r.request_id
            where p.id=? and r.request_id=? and r.message_id=? and r.status='PENDING' and q.status='PENDING'
            and r.lease_until>? and q.lease_until>? and p.expires_at>? and c.expires_at>?
            """,
            Long.class,
            turn.planId(),
            turn.requestId(),
            turn.messageId(),
            time(now),
            time(now),
            time(now),
            time(now)
        );
        if (
            root.revision() != turn.revision() ||
            !turn.requestId().equals(root.request()) ||
            live == null ||
            live != 1
        ) {
            throw conflict("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
        }
        var states = jdbc.query(
            "select state_json from ai_orchestration_collaboration where plan_id=?",
            (row, index) -> row.getString(1),
            turn.planId()
        );
        var before = states.isEmpty()
            ? initial(rootState, now)
            : parse(states.getFirst(), State.class);
        var replay = jdbc.query(
            "select request_digest from ai_orchestration_collaboration_events where plan_id=? and request_key=?",
            (row, index) -> row.getString(1),
            turn.planId(),
            requestKey
        );
        if (!replay.isEmpty()) {
            if (!requestDigest.equals(replay.getFirst())) throw conflict(
                "COLLABORATION_REQUEST_KEY_CONFLICT"
            );
            return before;
        }
        if (before.revision() != expectedRevision) throw conflict("COLLABORATION_REVISION_CHANGED");
        var next = mutation.apply(before, now);
        validate(before, next);
        persistRoot.accept(before, next);
        return persist(
            turn.planId(),
            turn.requestId(),
            requestKey,
            requestDigest,
            states,
            before,
            next,
            now
        );
    }

    public Consultations.Task cancel(
        Scope owner,
        UUID planId,
        UUID taskId,
        long expectedRevision,
        String requestKey
    ) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 153) throw invalid(
            "INVALID_COLLABORATION_REQUEST"
        );
        String key = "cancel:" + requestKey;
        String digest = SkillRegistry.digest(taskId + ":" + expectedRevision);
        return transactions.execute(status -> {
            var conversations = jdbc.query(
                """
                select id from ai_conversations where user_id=? and account_id=? and id=(
                  select conversation_id from ai_orchestrations where id=? and user_id=? and account_id=?
                ) for update
                """,
                (row, index) -> row.getObject(1, UUID.class),
                owner.userId(),
                owner.accountId(),
                planId,
                owner.userId(),
                owner.accountId()
            );
            if (conversations.isEmpty()) throw new Requirements.Rejected(404, "PLAN_UNAVAILABLE");
            var before = readInTransaction(owner, planId);
            var task = before.consultations().tasks().get(taskId);
            if (task == null) throw new Requirements.Rejected(404, "CONSULTATION_UNAVAILABLE");
            var replay = jdbc.query(
                """
                select request_digest,state_json from ai_orchestration_collaboration_events where plan_id=? and request_key=?
                """,
                (row, index) -> {
                    if (!digest.equals(row.getString(1))) throw conflict(
                        "COLLABORATION_REQUEST_KEY_CONFLICT"
                    );
                    return parse(row.getString(2), State.class).consultations().tasks().get(taskId);
                },
                planId,
                key
            );
            if (!replay.isEmpty()) return replay.getFirst();
            var tree = ConsultationPolicy.cancel(before.consultations(), taskId, expectedRevision);
            var next = new State(before.revision() + 1, before.requirements(), tree);
            validate(before, next);
            var states = jdbc.query(
                "select state_json from ai_orchestration_collaboration where plan_id=?",
                (row, index) -> row.getString(1),
                planId
            );
            UUID operationId = UUID.nameUUIDFromBytes(
                (planId + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            return persist(planId, operationId, key, digest, states, before, next, now())
                .consultations()
                .tasks()
                .get(taskId);
        });
    }

    private State persist(
        UUID planId,
        UUID operationId,
        String requestKey,
        String requestDigest,
        java.util.List<String> states,
        State before,
        State next,
        Instant now
    ) {
        String encoded = json(next);
        if (
            encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
            com.voltcanary.aiagent.orchestration.contract.ProtocolValidator.MAX_BYTES
        ) throw invalid("COLLABORATION_STATE_SIZE_LIMIT");
        if (states.isEmpty()) {
            jdbc.update(
                "insert into ai_orchestration_collaboration(plan_id,revision,state_json,updated_at) values(?,?,?,?)",
                planId,
                next.revision(),
                encoded,
                time(now)
            );
        } else {
            int updated = jdbc.update(
                "update ai_orchestration_collaboration set revision=?,state_json=?,updated_at=? where plan_id=? and revision=?",
                next.revision(),
                encoded,
                time(now),
                planId,
                before.revision()
            );
            if (updated != 1) throw conflict("COLLABORATION_REVISION_CHANGED");
        }
        jdbc.update(
            """
            insert into ai_orchestration_collaboration_events(plan_id,revision,request_key,request_digest,turn_id,before_digest,after_digest,state_json,created_at)
            values(?,?,?,?,?,?,?,?,?)
            """,
            planId,
            next.revision(),
            requestKey,
            requestDigest,
            operationId,
            SkillRegistry.digest(states.isEmpty() ? json(before) : states.getFirst()),
            SkillRegistry.digest(encoded),
            encoded,
            time(now)
        );
        // Return exactly the persisted JSON projection, including canonical JSON numeric/null nodes.
        return parse(encoded, State.class);
    }

    private void validate(State before, State next) {
        if (
            before.revision() == Requirements.MAX_REVISION ||
            next.revision() != before.revision() + 1 ||
            !before.requirements().planId().equals(next.requirements().planId()) ||
            !next
                .requirements()
                .slots()
                .keySet()
                .containsAll(before.requirements().slots().keySet()) ||
            next.requirements().revision() < before.requirements().revision() ||
            (next.requirements().revision() == before.requirements().revision() &&
                (!next.requirements().slots().equals(before.requirements().slots()) ||
                    !next.requirements().events().equals(before.requirements().events()))) ||
            next.consultations().revision() < before.consultations().revision() ||
            (next.consultations().revision() == before.consultations().revision() &&
                !next.consultations().equals(before.consultations())) ||
            !next.consultations().deadline().equals(before.consultations().deadline()) ||
            !next
                .consultations()
                .tasks()
                .keySet()
                .containsAll(before.consultations().tasks().keySet()) ||
            next.requirements().events().size() < before.requirements().events().size() ||
            !next
                .requirements()
                .events()
                .subList(0, before.requirements().events().size())
                .equals(before.requirements().events())
        ) {
            throw invalid("COLLABORATION_HISTORY_OR_PINS_CHANGED");
        }
        for (var old : before.consultations().tasks().values()) {
            var task = next.consultations().tasks().get(old.id());
            if (
                old.depth() != task.depth() ||
                old.reason() != task.reason() ||
                (task.revision() == old.revision() && !task.equals(old))
            ) throw invalid("CONSULTATION_COUNTER_OR_PINS_CHANGED");
            if (
                !old.request().equals(task.request()) ||
                !old.requestDigest().equals(task.requestDigest()) ||
                !old.obligationDigest().equals(task.obligationDigest()) ||
                task.exchanges() < old.exchanges() ||
                task.revision() < old.revision() ||
                task.continuationEpoch() < old.continuationEpoch() ||
                task.cancellationEpoch() < old.cancellationEpoch()
            ) throw invalid("CONSULTATION_COUNTER_OR_PINS_CHANGED");
        }
        for (var old : before.requirements().slots().values()) {
            var slot = next.requirements().slots().get(old.id());
            if (
                !old.path().equals(slot.path()) ||
                !old.schemaId().equals(slot.schemaId()) ||
                old.required() != slot.required() ||
                slot.revision() < old.revision() ||
                (slot.revision() == old.revision() && !slot.equals(old))
            ) {
                throw invalid("REQUIREMENT_CONTRACT_OR_REVISION_CHANGED");
            }
        }
    }

    private State initial(IntentPlans.State root, Instant now) {
        Timestamp origin = jdbc.queryForObject(
            "select min(created_at) from ai_conversation_messages where plan_id=?",
            Timestamp.class,
            root.planId()
        );
        if (origin == null) throw invalid("PLAN_ORIGIN_UNAVAILABLE");
        Instant limit = origin.toInstant().plusSeconds(3600);
        var budgets = jdbc.query(
            "select deadline_at from ai_model_budget_plans where plan_id=?",
            (row, index) -> row.getTimestamp(1).toInstant(),
            root.planId()
        );
        if (!budgets.isEmpty() && budgets.getFirst().isBefore(limit)) limit = budgets.getFirst();
        Instant deadline = root.expiresAt().isBefore(limit) ? root.expiresAt() : limit;
        return new State(
            0,
            Requirements.Snapshot.empty(root.planId()),
            new Consultations.Tree(root.planId(), 0, deadline, Map.of())
        );
    }

    private Instant now() {
        return jdbc
            .queryForObject(
                postgres ? "select clock_timestamp()" : "select current_timestamp",
                Timestamp.class
            )
            .toInstant();
    }

    private Timestamp time(Instant value) {
        return Timestamp.from(value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("COLLABORATION_ENCODING_FAILED", failure);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("COLLABORATION_STATE_INVALID", failure);
        }
    }

    private record Root(String json, long revision, UUID request) {}
}
