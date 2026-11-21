package com.voltcanary.device.variable.adapter;

import com.voltcanary.device.variable.application.VariableDefinitions;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VariableArchiveIntent {
    private final JdbcTemplate jdbc;

    /** Synchronous listener: disable intents share the archive transaction. */
    @EventListener
    public void archived(VariableDefinitions.Archived event) {
        jdbc.query("""
                select * from source_variable_bindings where variable_id=? and enabled=true
                """, (row, index) -> {
            jdbc.update("""
                    insert into variable_rollout_targets
                    (id,variable_id,revision,data_source_id,input_paths_json,enabled,status,created_by,updated_at,expected_binding_version)
                    values(?,?,?,?,?,false,'PENDING',?,?,?)
                    """, UUID.randomUUID(), event.variableId(), row.getInt("revision"),
                    row.getObject("data_source_id", UUID.class), row.getString("input_paths_json"),
                    event.actor(), Timestamp.from(Instant.now()), row.getInt("version"));
            return row.getObject("data_source_id", UUID.class);
        }, event.variableId());
    }
}
