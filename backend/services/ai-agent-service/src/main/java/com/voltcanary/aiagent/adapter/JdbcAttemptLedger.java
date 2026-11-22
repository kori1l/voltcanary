package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Limits;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting.Stop;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Parent then turn row locks serialize reservations across processes. Remote I/O is outside transactions. */
public class JdbcAttemptLedger implements AttemptLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    private record Balance(
        Limits limits,
        long calls,
        long input,
        long output,
        long cost,
        long deadline,
        boolean cancelled
    ) {}

    public JdbcAttemptLedger(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        ObjectMapper mapper
    ) {
        this(jdbc, transactions, mapper, Clock.systemUTC());
    }

    public JdbcAttemptLedger(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        ObjectMapper mapper,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void open(Turn turn, RoutingPolicy policy) {
        new ProtocolValidator(mapper).validate("routingPolicy", mapper.valueToTree(policy));
        if (!policy.turnLimits().currency().equals(policy.planLimits().currency())) throw new Stop(
            "CURRENCY_MISMATCH"
        );
        transactions.executeWithoutResult(transaction -> {
            var visible = jdbc.query(
                "select id from ai_conversations where id=? and user_id=? and account_id=? and expires_at>? for update",
                (row, index) -> row.getObject(1, UUID.class),
                turn.conversationId(),
                turn.owner().userId(),
                turn.owner().accountId(),
                now()
            );
            if (visible.isEmpty()) throw new Stop("CONVERSATION_UNAVAILABLE");
            var existing = jdbc.query(
                "select plan_id from ai_model_budget_plans where plan_id=?",
                (row, index) -> row.getObject(1, UUID.class),
                turn.planId()
            );
            if (existing.isEmpty()) {
                jdbc.update(
                    """
                    insert into ai_model_budget_plans(plan_id,conversation_id,user_id,account_id,policy_id,policy_version,limits_json,deadline_at)
                    values(?,?,?,?,?,?,?,?)
                    """,
                    turn.planId(),
                    turn.conversationId(),
                    turn.owner().userId(),
                    turn.owner().accountId(),
                    policy.policyId(),
                    policy.policyVersion(),
                    json(policy.planLimits()),
                    new Timestamp(Math.addExact(clock.millis(), policy.planLimits().maxElapsedMs()))
                );
            }
            lock(turn);
            var identity = jdbc.queryForMap(
                "select policy_id,policy_version,limits_json from ai_model_budget_plans where plan_id=?",
                turn.planId()
            );
            if (
                !identity.get("policy_id").equals(policy.policyId()) ||
                !identity.get("policy_version").equals(policy.policyVersion()) ||
                !identity.get("limits_json").equals(json(policy.planLimits()))
            ) throw new Stop("POLICY_CONFLICT");
            var turns = jdbc.query(
                "select policy_json from ai_model_budget_turns where plan_id=? and turn_id=?",
                (row, index) -> row.getString(1),
                turn.planId(),
                turn.turnId()
            );
            if (turns.isEmpty()) {
                jdbc.update(
                    "insert into ai_model_budget_turns(plan_id,turn_id,policy_json,limits_json,deadline_at) values(?,?,?,?,?)",
                    turn.planId(),
                    turn.turnId(),
                    json(policy),
                    json(policy.turnLimits()),
                    new Timestamp(Math.addExact(clock.millis(), policy.turnLimits().maxElapsedMs()))
                );
            } else if (!turns.getFirst().equals(json(policy))) throw new Stop("POLICY_CONFLICT");
        });
    }

    @Override
    public long remainingCost(Turn turn) {
        return transactions.execute(transaction -> {
            var balances = balances(turn);
            return Math.max(
                0,
                balances
                    .stream()
                    .mapToLong(balance -> balance.limits().maxCostMicros() - balance.cost())
                    .min()
                    .orElse(0)
            );
        });
    }

    @Override
    public long remainingTimeMillis(Turn turn) {
        return transactions.execute(transaction -> {
            var balances = balances(turn);
            if (balances.getFirst().cancelled()) throw new Stop("CANCELLED");
            long remaining = balances
                .stream()
                .mapToLong(balance -> balance.deadline() - clock.millis())
                .min()
                .orElse(0);
            if (remaining <= 0) throw new Stop("TIME_BUDGET_EXHAUSTED");
            return remaining;
        });
    }

    @Override
    public Receipt reserve(Turn turn, Attempt attempt) {
        return transactions.execute(transaction -> {
            var balances = balances(turn);
            var prior = jdbc.query(
                "select turn_id,attempt_json,status,response_json from ai_model_budget_attempts where plan_id=? and call_id=?",
                (row, index) -> {
                    if (
                        !turn.turnId().equals(row.getObject(1, UUID.class)) ||
                        !row.getString(2).equals(json(attempt))
                    ) throw new Stop("CALL_ID_CONFLICT");
                    return new Receipt(false, row.getString(3), row.getString(4));
                },
                turn.planId(),
                attempt.callId()
            );
            if (!prior.isEmpty()) return prior.getFirst();
            var policy = read(
                jdbc.queryForObject(
                    "select policy_json from ai_model_budget_turns where plan_id=? and turn_id=?",
                    String.class,
                    turn.planId(),
                    turn.turnId()
                ),
                RoutingPolicy.class
            );
            if (
                !policy.policyId().equals(attempt.policyId()) ||
                !policy.policyVersion().equals(attempt.policyVersion())
            ) throw new Stop("POLICY_CONFLICT");
            long input = attempt.route().size().inputTokens();
            long output = attempt.route().size().outputTokens();
            long cost = attempt.route().costMicros();
            if (input < 0 || output < 0 || cost < 0) throw new Stop("RESERVATION_INVALID");
            for (var balance : balances) check(balance, input, output, cost);
            jdbc.update(
                """
                insert into ai_model_budget_attempts(plan_id,turn_id,call_id,attempt_json,request_digest,status,
                charged_input,charged_output,charged_cost,created_at) values(?,?,?,?,?,'STARTED',?,?,?,?)
                """,
                turn.planId(),
                turn.turnId(),
                attempt.callId(),
                json(attempt),
                attempt.requestDigest(),
                input,
                output,
                cost,
                now()
            );
            charge(turn, 1, input, output, cost);
            return new Receipt(true, "STARTED", null);
        });
    }

    @Override
    public void finish(Turn turn, UUID call, String status, Usage usage, String response) {
        if (!List.of("SUCCEEDED", "FAILED", "UNCERTAIN").contains(status)) throw new Stop(
            "OUTCOME_INVALID"
        );
        transactions.executeWithoutResult(transaction -> {
            var balances = balances(turn);
            var row = jdbc.queryForMap(
                "select * from ai_model_budget_attempts where plan_id=? and turn_id=? and call_id=?",
                turn.planId(),
                turn.turnId(),
                call
            );
            if (!row.get("status").equals("STARTED")) {
                if (
                    row.get("status").equals(status) &&
                    java.util.Objects.equals(row.get("response_json"), response) &&
                    java.util.Objects.equals(
                        row.get("usage_json"),
                        usage == null ? null : json(usage)
                    )
                ) return;
                throw new Stop("CALL_ALREADY_FINISHED");
            }
            if (
                response != null &&
                (balances.getFirst().cancelled() ||
                    balances.stream().anyMatch(balance -> clock.millis() >= balance.deadline()))
            ) {
                throw new Stop("LATE_RESULT");
            }
            if (
                usage != null &&
                usage.costMicros() != null &&
                usage.inputTokens() != null &&
                usage.outputTokens() != null
            ) {
                if (
                    usage.inputTokens() < 0 || usage.outputTokens() < 0 || usage.costMicros() < 0
                ) throw new Stop("USAGE_INVALID");
                var attempt = read((String) row.get("attempt_json"), Attempt.class);
                if (
                    attempt.route().price() == null ||
                    !com.voltcanary.aiagent.orchestration.routing.ModelRouting.reconcile(
                        attempt.route().price(),
                        usage
                    ).equals(usage)
                ) {
                    throw new Stop("USAGE_PRICE_MISMATCH");
                }
                long output = usage.outputTokens();
                if (
                    attempt.route().price() != null &&
                    !attempt.route().price().reasoningIncludedInOutput()
                ) output = Math.addExact(output, usage.reasoningTokens());
                charge(
                    turn,
                    0,
                    usage.inputTokens() - ((Number) row.get("charged_input")).longValue(),
                    output - ((Number) row.get("charged_output")).longValue(),
                    usage.costMicros() - ((Number) row.get("charged_cost")).longValue()
                );
                jdbc.update(
                    "update ai_model_budget_attempts set charged_input=?,charged_output=?,charged_cost=? where plan_id=? and call_id=?",
                    usage.inputTokens(),
                    output,
                    usage.costMicros(),
                    turn.planId(),
                    call
                );
            }
            jdbc.update(
                "update ai_model_budget_attempts set status=?,usage_json=?,response_json=? where plan_id=? and call_id=?",
                status,
                usage == null ? null : json(usage),
                response,
                turn.planId(),
                call
            );
        });
    }

    @Override
    public void cancel(Turn turn) {
        transactions.executeWithoutResult(transaction -> {
            lock(turn);
            jdbc.update(
                "update ai_model_budget_plans set cancelled=true,revision=revision+1 where plan_id=?",
                turn.planId()
            );
        });
    }

    private void lock(Turn turn) {
        var conversations = jdbc.query(
            "select id from ai_conversations where id=? and user_id=? and account_id=? and expires_at>? for update",
            (row, index) -> row.getObject(1, UUID.class),
            turn.conversationId(),
            turn.owner().userId(),
            turn.owner().accountId(),
            now()
        );
        if (conversations.isEmpty()) throw new Stop("BUDGET_UNAVAILABLE");
        var rows = jdbc.query(
            """
            select p.plan_id from ai_model_budget_plans p
            where p.plan_id=? and p.conversation_id=? and p.user_id=? and p.account_id=?
            for update
            """,
            (row, index) -> row.getObject(1, UUID.class),
            turn.planId(),
            turn.conversationId(),
            turn.owner().userId(),
            turn.owner().accountId()
        );
        if (rows.isEmpty()) throw new Stop("BUDGET_UNAVAILABLE");
    }

    private List<Balance> balances(Turn turn) {
        lock(turn);
        var plan = jdbc.queryForMap(
            "select * from ai_model_budget_plans where plan_id=?",
            turn.planId()
        );
        var current = jdbc.queryForMap(
            "select * from ai_model_budget_turns where plan_id=? and turn_id=? for update",
            turn.planId(),
            turn.turnId()
        );
        return List.of(balance(plan), balance(current));
    }

    private Balance balance(java.util.Map<String, Object> row) {
        return new Balance(
            read((String) row.get("limits_json"), Limits.class),
            ((Number) row.get("calls")).longValue(),
            ((Number) row.get("input_tokens")).longValue(),
            ((Number) row.get("output_tokens")).longValue(),
            ((Number) row.get("cost_micros")).longValue(),
            instant(row.get("deadline_at")),
            Boolean.TRUE.equals(row.get("cancelled"))
        );
    }

    private static long instant(Object timestamp) {
        return timestamp instanceof Timestamp value
            ? value.toInstant().toEpochMilli()
            : ((java.time.OffsetDateTime) timestamp).toInstant().toEpochMilli();
    }

    private void check(Balance balance, long input, long output, long cost) {
        var limits = balance.limits();
        if (balance.cancelled()) throw new Stop("CANCELLED");
        if (clock.millis() >= balance.deadline()) throw new Stop("TIME_BUDGET_EXHAUSTED");
        if (
            balance.calls() >= limits.maxCalls() ||
            input > limits.maxInputTokens() - balance.input() ||
            output > limits.maxOutputTokens() - balance.output() ||
            cost > limits.maxCostMicros() - balance.cost()
        ) throw new Stop("BUDGET_EXHAUSTED");
    }

    private void charge(Turn turn, long calls, long input, long output, long cost) {
        jdbc.update(
            "update ai_model_budget_plans set calls=calls+?,input_tokens=input_tokens+?,output_tokens=output_tokens+?,cost_micros=cost_micros+?,revision=revision+1 where plan_id=?",
            calls,
            input,
            output,
            cost,
            turn.planId()
        );
        jdbc.update(
            "update ai_model_budget_turns set calls=calls+?,input_tokens=input_tokens+?,output_tokens=output_tokens+?,cost_micros=cost_micros+?,revision=revision+1 where plan_id=? and turn_id=?",
            calls,
            input,
            output,
            cost,
            turn.planId(),
            turn.turnId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new Stop("BUDGET_SERIALIZATION");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new Stop("BUDGET_SERIALIZATION");
        }
    }
}
