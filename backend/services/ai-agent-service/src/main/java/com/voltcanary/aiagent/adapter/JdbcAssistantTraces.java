package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.TraceProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts.Page;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.port.AssistantTraces;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import com.voltcanary.aiagent.trace.TracePayload;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcAssistantTraces implements AssistantTraces {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationStore conversations;
    private final TraceProperties properties;
    private final TracePayload payloads;

    public JdbcAssistantTraces(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        ConversationStore conversations,
        TraceProperties properties,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        this.conversations = conversations;
        this.properties = properties;
        payloads = new TracePayload(mapper);
    }

    public Correlation input(Scope owner, UUID conversation, UUID request) {
        conversations.get(owner, conversation);
        var rows = jdbc.query(
            "select id,coalesce(turn_id,request_id) as turn_id from ai_conversation_messages where conversation_id=? and request_id=? and kind='INPUT'",
            (row, index) ->
                new Correlation(
                    owner,
                    conversation,
                    row.getObject("id", UUID.class),
                    row.getObject("turn_id", UUID.class),
                    request
                ),
            conversation,
            request
        );
        if (rows.isEmpty()) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Submitted message unavailable"
        );
        return rows.getFirst();
    }

    public java.util.Optional<Correlation> plan(Scope owner, UUID plan) {
        var rows = jdbc.query(
            """
            select c.id,m.request_id from ai_conversations c join ai_conversation_messages m on m.conversation_id=c.id
            where c.user_id=? and c.account_id=? and c.expires_at>? and m.plan_id=? and m.kind='REPLY' order by c.created_at desc limit 1
            """,
            (row, index) ->
                input(owner, row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            owner.userId(),
            owner.accountId(),
            Timestamp.from(Instant.now()),
            plan
        );
        return rows.stream().findFirst();
    }

    @Override
    public void append(
        Correlation correlation,
        UUID attempt,
        String stage,
        String status,
        String code,
        Long duration,
        Map<String, Object> details
    ) {
        boolean[] limited = { false };
        transactions.executeWithoutResult(transaction -> {
            var owner = correlation.owner();
            var locked = jdbc.query(
                "select id from ai_conversations where id=? and user_id=? and account_id=? and expires_at>? for update",
                (row, index) -> row.getObject(1, UUID.class),
                correlation.conversationId(),
                owner.userId(),
                owner.accountId(),
                Timestamp.from(Instant.now())
            );
            if (locked.isEmpty()) throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation unavailable"
            );
            var payload = payloads.encode(details, properties.getMaxPayloadBytes());
            UUID desiredId = eventId(
                correlation,
                attempt,
                stage,
                status,
                code,
                duration,
                payload.json()
            );
            Integer recorded = jdbc.queryForObject(
                "select count(*) from ai_assistant_trace_events where id=?",
                Integer.class,
                desiredId
            );
            if (recorded != null && recorded > 0) return;
            if ("PROCESS_INTERRUPTED".equals(code) || "RECEIPT_RECOVERY".equals(code)) {
                Integer terminal = jdbc.queryForObject(
                    "select count(*) from ai_assistant_trace_events where conversation_id=? and attempt_id=? and stage=? and status in ('SUCCEEDED','FAILED','UNKNOWN')",
                    Integer.class,
                    correlation.conversationId(),
                    attempt,
                    stage
                );
                if (terminal != null && terminal > 0) return;
            }
            Long count = jdbc.queryForObject(
                "select count(*) from ai_assistant_trace_events where conversation_id=? and turn_id=?",
                Long.class,
                correlation.conversationId(),
                correlation.turnId()
            );
            Long used = jdbc.queryForObject(
                "select coalesce(sum(payload_bytes),0) from ai_assistant_trace_events where conversation_id=? and turn_id=?",
                Long.class,
                correlation.conversationId(),
                correlation.turnId()
            );
            Integer capturedLimit = jdbc.queryForObject(
                "select count(*) from ai_assistant_trace_events where conversation_id=? and turn_id=? and code in ('CAPTURE_EVENT_LIMIT','CAPTURE_BYTE_LIMIT')",
                Integer.class,
                correlation.conversationId(),
                correlation.turnId()
            );
            if (
                count == null ||
                used == null ||
                count >= properties.getMaxEvents() ||
                (capturedLimit != null && capturedLimit > 0)
            ) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TRACE_CAPTURE_LIMIT"
                );
            }
            String storedStage = stage;
            String storedStatus = status;
            String storedCode = code;
            if (
                count >= properties.getMaxEvents() - 1 ||
                used + payload.bytes() > properties.getMaxTurnBytes() - 256
            ) {
                storedStage = "CAPTURE";
                storedStatus = "UNKNOWN";
                storedCode =
                    count >= properties.getMaxEvents() - 1
                        ? "CAPTURE_EVENT_LIMIT"
                        : "CAPTURE_BYTE_LIMIT";
                payload = payloads.encode(
                    Map.of(
                        "captureLimit",
                        true,
                        "retainedEventCount",
                        count,
                        "retainedPayloadBytes",
                        used
                    ),
                    properties.getMaxPayloadBytes()
                );
                limited[0] = true;
            }
            Long sequence = jdbc.queryForObject(
                "select coalesce(max(sequence),0)+1 from ai_assistant_trace_events where conversation_id=?",
                Long.class,
                correlation.conversationId()
            );
            jdbc.update(
                """
                insert into ai_assistant_trace_events(id,conversation_id,sequence,schema_version,message_id,turn_id,request_id,
                attempt_id,review_id,action_id,model_run_id,stage,status,code,duration_ms,payload_json,payload_bytes,truncated,created_at)
                values(?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                eventId(
                    correlation,
                    attempt,
                    storedStage,
                    storedStatus,
                    storedCode,
                    duration,
                    payload.json()
                ),
                correlation.conversationId(),
                sequence,
                correlation.messageId(),
                correlation.turnId(),
                correlation.requestId(),
                attempt,
                reference(details, "reviewId"),
                reference(details, "actionId"),
                reference(details, "modelRunId"),
                storedStage,
                storedStatus,
                storedCode,
                duration,
                payload.json(),
                payload.bytes(),
                payload.truncated() || limited[0],
                Timestamp.from(Instant.now())
            );
        });
        if (limited[0]) throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "TRACE_CAPTURE_LIMIT"
        );
    }

    public Page<Event> read(Scope owner, UUID conversation, UUID turn, long after, int limit) {
        conversations.get(owner, conversation);
        if (after < 0 || limit < 1 || limit > 100) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid trace cursor/page size"
        );
        reconcile(owner, conversation, turn);
        var rows = jdbc.query(
            "select * from ai_assistant_trace_events where conversation_id=? and turn_id=? and sequence>? order by sequence limit ?",
            (row, index) ->
                new Event(
                    row.getObject("id", UUID.class),
                    row.getInt("schema_version"),
                    row.getLong("sequence"),
                    conversation,
                    row.getObject("message_id", UUID.class),
                    turn,
                    row.getObject("request_id", UUID.class),
                    row.getObject("attempt_id", UUID.class),
                    row.getObject("review_id", UUID.class),
                    row.getObject("action_id", UUID.class),
                    row.getObject("model_run_id", UUID.class),
                    row.getString("stage"),
                    row.getString("status"),
                    row.getString("code"),
                    row.getObject("duration_ms", Long.class),
                    payloads.decode(row.getString("payload_json")),
                    row.getBoolean("truncated"),
                    row.getTimestamp("created_at").toInstant()
                ),
            conversation,
            turn,
            after,
            limit + 1
        );
        return new Page<>(
            rows.stream().limit(limit).toList(),
            rows.size() > limit ? Long.toString(rows.get(limit - 1).sequence()) : null
        );
    }

    private void reconcile(Scope owner, UUID conversation, UUID turn) {
        var starts = jdbc.query(
            """
            select * from ai_assistant_trace_events e where e.conversation_id=? and e.turn_id=? and e.status='STARTED'
            and e.created_at<? and not exists(select 1 from ai_assistant_trace_events f where f.conversation_id=e.conversation_id
            and f.attempt_id=e.attempt_id and f.stage=e.stage and f.status in ('SUCCEEDED','FAILED','UNKNOWN'))
            """,
            (row, index) ->
                new Interrupted(
                    row.getObject("attempt_id", UUID.class),
                    row.getString("stage"),
                    row.getObject("message_id", UUID.class),
                    row.getObject("request_id", UUID.class),
                    row.getObject("review_id", UUID.class),
                    row.getObject("action_id", UUID.class)
                ),
            conversation,
            turn,
            Timestamp.from(Instant.now().minusSeconds(600))
        );
        for (var start : starts) {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("replayed", false);
            String status = "UNKNOWN";
            String code = "PROCESS_INTERRUPTED";
            if (start.review() != null) {
                details.put("reviewId", start.review());
                var receipt = jdbc.query(
                    "select status from ai_reviewed_plans where id=? and user_id=? and account_id=?",
                    (row, index) -> row.getString(1),
                    start.review(),
                    owner.userId(),
                    owner.accountId()
                );
                if (start.action() != null) {
                    details.put("actionId", start.action());
                    if (!receipt.isEmpty()) receipt = jdbc.query(
                        "select status from ai_plan_actions where plan_id=? and action_id=?",
                        (row, index) -> row.getString(1),
                        start.review(),
                        start.action()
                    );
                }
                if (
                    !receipt.isEmpty() &&
                    java.util.Set.of("SUCCESS", "FAILED", "PARTIAL_SUCCESS", "UNKNOWN").contains(
                        receipt.getFirst()
                    )
                ) {
                    details.put("executionStatus", receipt.getFirst());
                    status = "SUCCESS".equals(receipt.getFirst())
                        ? "SUCCEEDED"
                        : "UNKNOWN".equals(receipt.getFirst())
                          ? "UNKNOWN"
                          : "FAILED";
                    code = "RECEIPT_RECOVERY";
                }
            }
            try {
                append(
                    new Correlation(owner, conversation, start.message(), turn, start.request()),
                    start.attempt(),
                    start.stage(),
                    status,
                    code,
                    null,
                    details
                );
            } catch (ResponseStatusException unavailable) {
                if (!"TRACE_CAPTURE_LIMIT".equals(unavailable.getReason())) throw unavailable;
                // Existing bounded events and the explicit limit marker remain readable.
            }
        }
    }

    private record Interrupted(
        UUID attempt,
        String stage,
        UUID message,
        UUID request,
        UUID review,
        UUID action
    ) {}

    private UUID eventId(
        Correlation correlation,
        UUID attempt,
        String stage,
        String status,
        String code,
        Long duration,
        String payload
    ) {
        return UUID.nameUUIDFromBytes(
            (
                correlation.conversationId() +
                ":" +
                correlation.turnId() +
                ":" +
                attempt +
                ":" +
                stage +
                ":" +
                status +
                ":" +
                code +
                ":" +
                duration +
                ":" +
                payload
            ).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private UUID reference(Map<String, Object> details, String key) {
        Object value = details.get(key);
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
