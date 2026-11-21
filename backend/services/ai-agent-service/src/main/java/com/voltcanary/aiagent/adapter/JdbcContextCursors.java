package com.voltcanary.aiagent.adapter;

import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opaque restart-safe cursor. Binding is a digest of scope/conversation/plan/step/skill/loader/arguments. */
@RequiredArgsConstructor
public final class JdbcContextCursors implements ContextSources.Cursors {
    private final JdbcTemplate jdbc;

    @Override
    public String issue(String binding, ContextSources.Position position, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into ai_context_cursors(id,binding,after_sequence,through_sequence,expires_at) values(?,?,?,?,?)",
                id, binding, position.after(), position.through(), Timestamp.from(expiresAt));
        return id;
    }
    @Override
    public ContextSources.Position resolve(String token, String binding, Instant now) {
        var rows = jdbc.query("select after_sequence,through_sequence from ai_context_cursors where id=? and binding=? and expires_at>?",
                (row, index) -> new ContextSources.Position(row.getLong(1), row.getLong(2)), token, binding, Timestamp.from(now));
        if (rows.size() != 1) throw IntentPlans.reject("CONTEXT_CURSOR_INVALID_OR_EXPIRED");
        return rows.getFirst();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.ai.conversations.cleanup-delay:3600000}")
    public void purge() {
        jdbc.update("delete from ai_context_cursors where expires_at<?", Timestamp.from(Instant.now()));
    }
}
