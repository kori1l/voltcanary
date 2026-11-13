package com.voltcanary.aiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.config.ConversationProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts.Conversation;
import com.voltcanary.aiagent.conversation.ConversationContracts.Message;
import com.voltcanary.aiagent.conversation.ConversationContracts.Page;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short serialized writes; provider and domain calls always happen outside these transactions. */
@Repository
public class ConversationStore {
    public record Attempt(String status, UUID planId, boolean claimed) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationProperties properties;
    private final ObjectMapper mapper;

    public ConversationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ConversationProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Conversation create(Scope user, UUID id) {
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    insert into ai_conversations(id,user_id,account_id,title,revision,created_at,updated_at,expires_at)
                    values(?,?,?,'Новый разговор',0,?,?,?)
                    """, id, user.userId(), user.accountId(), time(now), time(now), time(now.plus(properties.getRetention())));
        } catch (DuplicateKeyException duplicate) {
            // A lost create response may be retried with the same client-generated ID.
        }
        var conversation = get(user, id);
        select(user, id);
        return conversation;
    }

    public Conversation get(Scope user, UUID id) {
        var result = jdbc.query("select * from ai_conversations where id=? and user_id=? and account_id=? and expires_at>?",
                (row, index) -> conversation(row), id, user.userId(), user.accountId(), time(Instant.now()));
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation unavailable");
        return result.getFirst();
    }

    public Page<Conversation> list(Scope user, UUID before, int limit) {
        int size = size(limit);
        Conversation cursor = before == null ? null : get(user, before);
        String where = cursor == null ? "" : " and (created_at<? or (created_at=? and id<?))";
        var params = new java.util.ArrayList<Object>(List.of(user.userId(), user.accountId(), time(Instant.now())));
        if (cursor != null) params.addAll(List.of(time(cursor.createdAt()), time(cursor.createdAt()), cursor.id()));
        params.add(size + 1);
        var rows = jdbc.query("select * from ai_conversations where user_id=? and account_id=? and expires_at>?"
                + where + " order by created_at desc,id desc limit ?", (row, index) -> conversation(row), params.toArray());
        return new Page<>(rows.stream().limit(size).toList(), rows.size() > size ? rows.get(size - 1).id().toString() : null);
    }

    public UUID selected(Scope user) {
        var rows = jdbc.query("""
                select s.conversation_id from ai_conversation_selection s join ai_conversations c on c.id=s.conversation_id
                where s.user_id=? and s.account_id=? and c.expires_at>?
                """, (row, index) -> row.getObject(1, UUID.class), user.userId(), user.accountId(), time(Instant.now()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void select(Scope user, UUID id) {
        get(user, id);
        // Separate statements avoid leaving a PostgreSQL transaction aborted on an insert race.
        try {
            jdbc.update("insert into ai_conversation_selection(user_id,account_id,conversation_id) values(?,?,?)",
                    user.userId(), user.accountId(), id);
        } catch (DuplicateKeyException duplicate) {
            jdbc.update("update ai_conversation_selection set conversation_id=? where user_id=? and account_id=?",
                    id, user.userId(), user.accountId());
        }
    }

    public Page<Message> messages(Scope user, UUID id, long after, int limit) {
        get(user, id);
        int size = size(limit);
        var rows = jdbc.query("select * from ai_conversation_messages where conversation_id=? and sequence>? order by sequence limit ?",
                (row, index) -> message(row), id, after, size + 1);
        return new Page<>(rows.stream().limit(size).toList(), rows.size() > size ? Long.toString(rows.get(size - 1).sequence()) : null);
    }

    public Attempt begin(Scope user, UUID id, ConversationContracts.Submit request, String hash) {
        return transactions.execute(status -> {
            lock(user, id);
            var previous = jdbc.query("select * from ai_conversation_requests where conversation_id=? and request_id=?",
                    (row, index) -> {
                        if (!hash.equals(row.getString("payload_hash"))) throw conflict("Request payload changed");
                        return new Attempt(row.getString("status"), row.getObject("plan_id", UUID.class), false);
                    }, id, request.requestId());
            if (!previous.isEmpty()) return previous.getFirst();
            var current = get(user, id);
            if (current.revision() != request.expectedRevision()) throw conflict("Conversation changed; reload current history");
            Integer pending = jdbc.queryForObject("select count(*) from ai_conversation_requests where conversation_id=? and status='PENDING'",
                    Integer.class, id);
            if (pending != null && pending > 0) throw conflict("Conversation has a pending request; reload its status");
            Instant now = Instant.now();
            jdbc.update("insert into ai_conversation_requests(conversation_id,request_id,payload_hash,status,lease_until) values(?,?,?,'PENDING',?)",
                    id, request.requestId(), hash, time(now.plus(properties.getLease())));
            String input = request.displayText() == null || request.displayText().isBlank() ? request.text() : request.displayText();
            append(id, request.requestId(), "user", "INPUT", input, null, request.metadata() == null ? null : request.metadata().taskId(), "PENDING");
            jdbc.update("update ai_conversation_messages set automatic=? where conversation_id=? and request_id=? and kind='INPUT'",
                    request.automatic(), id, request.requestId());
            jdbc.update("update ai_conversations set title=? where id=? and title='Новый разговор'",
                    ConversationRedaction.text(input).substring(0, Math.min(65, ConversationRedaction.text(input).length())), id);
            return new Attempt("PENDING", null, true);
        });
    }

    public void finish(Scope user, UUID id, UUID request, UUID plan, UUID task, String text, String outcome) {
        transactions.executeWithoutResult(status -> {
            lock(user, id);
            int changed = jdbc.update("update ai_conversation_requests set status=?,plan_id=? where conversation_id=? and request_id=? and status='PENDING'",
                    outcome, plan, id, request);
            if (changed == 0) return;
            append(id, request, "assistant", "REPLY", text, plan, task, outcome);
            if (plan != null) jdbc.update("update ai_conversations set last_plan_id=? where id=?", plan, id);
        });
    }

    public String pending(Scope user, UUID id) {
        get(user, id);
        var rows = jdbc.query("select status from ai_conversation_requests where conversation_id=? order by lease_until desc limit 1",
                (row, index) -> row.getString(1), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Append receipts after a lost execute response, including after an application restart. Never executes tools. */
    public void reconcile(Scope user, UUID id) {
        transactions.executeWithoutResult(status -> {
            lock(user, id);
            var interrupted = jdbc.query("select request_id from ai_conversation_requests where conversation_id=? and status='PENDING' and lease_until<?",
                    (row, index) -> row.getObject(1, UUID.class), id, time(Instant.now()));
            for (UUID request : interrupted) {
                jdbc.update("update ai_conversation_requests set status='UNKNOWN' where conversation_id=? and request_id=?", id, request);
                append(id, request, "assistant", "REPLY", "Запрос был прерван. Результат неизвестен; автоматический повтор не выполнялся.", null, null, "UNKNOWN");
            }
            var receipts = jdbc.query("""
                    select distinct p.id,p.status,p.result_json from ai_reviewed_plans p
                    join ai_conversation_messages m on m.plan_id=p.id
                    where m.conversation_id=? and p.user_id=? and p.account_id=?
                    and p.status not in ('READY','NEEDS_INPUT','RUNNING')
                    """, (row, index) -> new Receipt(row.getObject(1, UUID.class), row.getString(2), row.getString(3)), id, user.userId(), user.accountId());
            for (var receipt : receipts) {
                UUID receiptEvent = UUID.nameUUIDFromBytes((receipt.plan() + ":" + receipt.status()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Integer count = jdbc.queryForObject("select count(*) from ai_conversation_messages where conversation_id=? and plan_id=? and kind='EXECUTION' and outcome=?",
                        Integer.class, id, receipt.plan(), receipt.status());
                if (count != null && count == 0) append(id, receiptEvent, "assistant", "EXECUTION",
                        executionText(receipt), receipt.plan(), null, receipt.status());
            }
        });
    }

    private record Receipt(UUID plan, String status, String json) { }

    /** Derive durable referents from receipts, including partial plans with a successful create action. */
    public List<ConversationResource> resources(Scope user, UUID id) {
        get(user, id);
        return jdbc.query("""
                select p.id,p.result_json,max(m.sequence) as message_revision from ai_reviewed_plans p
                join ai_conversation_messages m on m.plan_id=p.id
                where m.conversation_id=? and p.user_id=? and p.account_id=?
                and m.kind='REPLY' and p.status in ('SUCCESS','PARTIAL_SUCCESS') and p.result_json is not null
                group by p.id,p.result_json order by message_revision desc
                """, (row, index) -> {
            var result = new java.util.ArrayList<ConversationResource>();
            try {
                var receipt = mapper.readValue(row.getString("result_json"), ExecutePlanResponse.class);
                UUID review = row.getObject("id", UUID.class);
                if (!review.equals(receipt.planId())) return List.<ConversationResource>of();
                for (var action : receipt.actions()) {
                    if (!"CREATE_DEVICE".equals(action.tool()) || !"SUCCESS".equals(action.status()) || action.result() == null) continue;
                    Object device = action.result().get("deviceId");
                    if (device == null || action.actionId() == null) continue;
                    String name = "Устройство " + device;
                    if (action.result().get("device") instanceof java.util.Map<?, ?> value && value.get("name") instanceof String label) name = label;
                    Object source = action.result().get("sourceId");
                    result.add(new ConversationResource(UUID.fromString(device.toString()), source instanceof String text ? text : null,
                            name, review, action.actionId(), row.getLong("message_revision")));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
                // Malformed legacy receipts cannot become resource identities.
            }
            return List.copyOf(result);
        }, id, user.userId(), user.accountId()).stream().flatMap(List::stream).toList();
    }

    private String executionText(Receipt receipt) {
        try {
            var result = mapper.readValue(receipt.json(), ExecutePlanResponse.class);
            var summaries = result.actions().stream().map(action -> action.result() == null ? null : action.result().get("summary"))
                    .filter(String.class::isInstance).map(String.class::cast).toList();
            return "Результат выполнения: " + receipt.status() + (summaries.isEmpty() ? "" : "\n\n" + String.join("\n\n", summaries));
        } catch (Exception invalid) {
            return "Результат выполнения: " + receipt.status();
        }
    }

    @Scheduled(fixedDelayString = "${app.ai.conversations.cleanup-delay:3600000}")
    public void purge() {
        jdbc.update("delete from ai_conversations where expires_at<?", time(Instant.now()));
    }

    private void lock(Scope user, UUID id) {
        var rows = jdbc.query("select id from ai_conversations where id=? and user_id=? and account_id=? and expires_at>? for update",
                (row, index) -> row.getObject(1, UUID.class), id, user.userId(), user.accountId(), time(Instant.now()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation unavailable");
    }

    private void append(UUID id, UUID request, String role, String kind, String text, UUID plan, UUID task, String outcome) {
        Long revision = jdbc.queryForObject("select revision from ai_conversations where id=?", Long.class, id);
        long next = (revision == null ? 0 : revision) + 1;
        String safe = ConversationRedaction.text(text);
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_conversation_messages(id,conversation_id,sequence,request_id,role,kind,display_text,redacted,
                plan_id,task_id,turn_id,outcome,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), id, next, request, role, kind, safe, ConversationRedaction.marked(text, safe), plan, task,
                "EXECUTION".equals(kind) ? null : request, outcome, time(now));
        jdbc.update("update ai_conversations set revision=?,updated_at=? where id=?", next, time(now), id);
    }

    private Conversation conversation(ResultSet row) throws SQLException {
        return new Conversation(row.getObject("id", UUID.class), row.getString("title"), row.getLong("revision"),
                row.getObject("last_plan_id", UUID.class), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getTimestamp("expires_at").toInstant());
    }
    private Message message(ResultSet row) throws SQLException {
        return new Message(row.getObject("id", UUID.class), row.getLong("sequence"), row.getObject("request_id", UUID.class),
                row.getString("role"), row.getString("kind"), row.getString("display_text"), row.getBoolean("redacted"), row.getBoolean("automatic"),
                row.getObject("plan_id", UUID.class), row.getObject("task_id", UUID.class), row.getObject("turn_id", UUID.class),
                row.getString("outcome"), row.getTimestamp("created_at").toInstant());
    }
    private int size(int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be 1..100");
        return limit;
    }
    private Timestamp time(Instant instant) { return Timestamp.from(instant); }
    private ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT, text); }
}
