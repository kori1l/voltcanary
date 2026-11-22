package com.voltcanary.device.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.device.dto.ProcessingBundleViews.Entry;
import com.voltcanary.device.dto.ProcessingBundleViews.RuntimeTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProcessingBundleRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public List<Entry> list(long after, int limit, UUID sourceId, Long generation) {
        String sql =
            "select sequence_id,bundle_json from source_processing_bundles where sequence_id>?";
        List<Object> arguments = new ArrayList<>(List.of(after));
        if (sourceId != null) {
            sql += " and data_source_id=?";
            arguments.add(sourceId);
        }
        if (generation != null) {
            sql += " and generation=?";
            arguments.add(generation);
        }
        sql += " order by sequence_id limit ?";
        arguments.add(limit);
        return jdbc.query(
            sql,
            (row, index) -> {
                try {
                    return new Entry(
                        row.getLong("sequence_id"),
                        mapper.readValue(
                            row.getString("bundle_json"),
                            SourceProcessingConfigEvent.class
                        )
                    );
                } catch (JsonProcessingException invalid) {
                    throw new IllegalStateException("Invalid stored processing bundle", invalid);
                }
            },
            arguments.toArray()
        );
    }

    public boolean active(UUID device, UUID source, UUID variable) {
        return (
            jdbc.queryForObject(
                """
                select count(*) from source_variable_bindings b
                join data_sources s on s.id=b.data_source_id join devices d on d.id=s.device_id
                join accounts a on a.id=d.account_id join device_variables v on v.id=b.variable_id
                where d.id=? and s.id=? and v.id=? and a.status='ACTIVE' and d.status='ACTIVE'
                and s.enabled=true and s.status='ACTIVE' and b.enabled=true and v.archived=false
                """,
                Integer.class,
                device,
                source,
                variable
            ) > 0
        );
    }

    public List<RuntimeTarget> activeBatch(UUID device, List<RuntimeTarget> targets) {
        String alternatives = String.join(
            " or ",
            Collections.nCopies(targets.size(), "(s.id=? and v.id=?)")
        );
        List<Object> arguments = new ArrayList<>();
        arguments.add(device);
        targets.forEach(target -> {
            arguments.add(target.dataSourceId());
            arguments.add(target.variableId());
        });
        return jdbc.query(
            """
            select s.id source_id,v.id variable_id from source_variable_bindings b
            join data_sources s on s.id=b.data_source_id join devices d on d.id=s.device_id
            join accounts a on a.id=d.account_id join device_variables v on v.id=b.variable_id
            where d.id=? and a.status='ACTIVE' and d.status='ACTIVE' and s.enabled=true
            and s.status='ACTIVE' and b.enabled=true and v.archived=false and (
            """ +
                alternatives +
                ")",
            (row, index) ->
                new RuntimeTarget(
                    row.getObject("source_id", UUID.class),
                    row.getObject("variable_id", UUID.class)
                ),
            arguments.toArray()
        );
    }
}
