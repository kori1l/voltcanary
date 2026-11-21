package com.voltcanary.device.variable.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.variable.domain.VariableBinding;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VariableBindingRows {
    public record Source(UUID accountId, UUID deviceId, UUID sourceId, String externalId,
            String deviceType, String sourceType, String mappingJson, boolean active) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Source source(UUID account, UUID device, UUID source, boolean lock) {
        if (lock) {
            jdbc.queryForList("select id from devices where id=? and account_id=? for update", device, account);
            jdbc.queryForList("select id from data_sources where id=? for update", source);
        }
        return jdbc.query("""
                select a.id account_id,d.id device_id,s.id,s.source_id,t.code,s.source_type,s.mapping_json,
                s.enabled,s.status,d.status device_status from data_sources s
                join devices d on d.id=s.device_id join accounts a on a.id=d.account_id
                join device_types t on t.id=d.device_type_id
                where a.id=? and a.status='ACTIVE' and d.id=? and s.id=?
                """, (row, index) -> new Source(row.getObject("account_id", UUID.class),
                row.getObject("device_id", UUID.class), row.getObject("id", UUID.class),
                row.getString("source_id"), row.getString("code"), row.getString("source_type"),
                row.getString("mapping_json"), row.getBoolean("enabled")
                && "ACTIVE".equals(row.getString("status"))
                && "ACTIVE".equals(row.getString("device_status"))), account, device, source)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Data source not found"));
    }

    public List<VariableBinding> bindings(UUID source) {
        return jdbc.query("select * from source_variable_bindings where data_source_id=? order by variable_id",
                (row, index) -> new VariableBinding(row.getObject("data_source_id", UUID.class),
                row.getObject("variable_id", UUID.class), row.getInt("revision"), row.getInt("version"),
                paths(row.getString("input_paths_json")), row.getBoolean("enabled"),
                row.getBoolean("inherited")), source);
    }

    public Map<String, String> paths(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>() { }); }
        catch (Exception failure) { throw new IllegalStateException("Invalid variable input paths", failure); }
    }
}
