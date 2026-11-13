package com.voltcanary.aiagent.adapter;

import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.DialogueMessagesItem;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded reads from the existing transcript; no reconciliation, summarization or second history store. */
public final class JdbcContextHistory implements ContextSources.History {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcContextHistory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }
    private void check(Session session, UUID conversation) {
        Long count = jdbc.queryForObject("select count(*) from ai_conversations where id=? and user_id=? and account_id=? and expires_at>?",
                Long.class, conversation, session.owner().userId(), session.owner().accountId(), java.sql.Timestamp.from(clock.instant()));
        if (count == null || count != 1) throw new IntentPlans.Rejected(404, "CONVERSATION_UNAVAILABLE");
    }
    @Override
    public long through(Session session, UUID conversation) {
        check(session, conversation);
        return jdbc.queryForObject("select coalesce(max(sequence),0) from ai_conversation_messages where conversation_id=?", Long.class, conversation);
    }
    @Override
    public long count(Session session, UUID conversation, long through) {
        check(session, conversation);
        return jdbc.queryForObject("select count(*) from ai_conversation_messages where conversation_id=? and sequence<=?", Long.class, conversation, through);
    }
    @Override
    public List<DialogueMessagesItem> required(Session session, UUID conversation, long through, Set<String> ids) {
        check(session, conversation);
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 12) throw IntentPlans.reject("MANDATORY_DIALOGUE_REQUIRES_NARROWING");
        var args = new ArrayList<Object>(List.of(conversation, through));
        ids.forEach(id -> args.add(UUID.fromString(id)));
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var rows = jdbc.query("select * from ai_conversation_messages where conversation_id=? and sequence<=? and id in (" + placeholders + ") order by sequence",
                (row, index) -> message(row), args.toArray());
        if (rows.size() != ids.size()) throw IntentPlans.reject("MANDATORY_DIALOGUE_UNAVAILABLE");
        return rows;
    }
    @Override
    public List<DialogueMessagesItem> recent(Session session, UUID conversation, long through, int limit) {
        check(session, conversation);
        limit(limit);
        return jdbc.query("select * from ai_conversation_messages where conversation_id=? and sequence<=? order by sequence desc limit ?",
                (row, index) -> message(row), conversation, through, limit);
    }
    @Override
    public List<DialogueMessagesItem> page(Session session, UUID conversation, long through, long after, int limit) {
        check(session, conversation);
        limit(limit);
        return jdbc.query("select * from ai_conversation_messages where conversation_id=? and sequence>? and sequence<=? order by sequence limit ?",
                (row, index) -> message(row), conversation, after, through, limit);
    }
    private static void limit(int limit) {
        if (limit < 1 || limit > 13) throw IntentPlans.reject("HISTORY_PAGE_LIMIT");
    }
    private DialogueMessagesItem message(ResultSet row) throws SQLException {
        String kind = row.getString("kind");
        kind = switch (kind) {
            case "EXECUTION" -> "EXECUTION_RESULT";
            case "CONFIRMATION", "CONTROL" -> "INPUT";
            case "INPUT", "REPLY", "QUESTION", "EXECUTION_RESULT" -> kind;
            default -> throw IntentPlans.reject("CONTEXT_HISTORY_KIND_UNSUPPORTED");
        };
        return new DialogueMessagesItem(row.getObject("id", UUID.class).toString(), row.getLong("sequence"),
                row.getString("role").toUpperCase(Locale.ROOT), kind, row.getString("display_text"), null, null, null);
    }
}
