package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.device.config.InternalServiceAuth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalProcessingBundlesController {
    public record Entry(long cursor, SourceProcessingConfigEvent bundle) { }
    public record RuntimeTarget(UUID dataSourceId, UUID variableId) { }
    public record RuntimeRequest(UUID deviceId, List<RuntimeTarget> targets) { }
    private final InternalServiceAuth auth;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InternalProcessingBundlesController(InternalServiceAuth auth, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/internal/source-processing-bundles")
    public List<Entry> list(@RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @RequestParam(value = "after", defaultValue = "0") long after,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "sourceId", required = false) UUID sourceId,
            @RequestParam(value = "generation", required = false) Long generation) {
        auth.require(token);
        if (after < 0 || limit < 1 || limit > 500 || generation != null && (sourceId == null || generation < 1)) {
            throw new IllegalArgumentException("Invalid processing bundle cursor/filter");
        }
        String sql = "select sequence_id,bundle_json from source_processing_bundles where sequence_id>?";
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>(List.of(after));
        if (sourceId != null) { sql += " and data_source_id=?"; arguments.add(sourceId); }
        if (generation != null) { sql += " and generation=?"; arguments.add(generation); }
        sql += " order by sequence_id limit ?";
        arguments.add(limit);
        return jdbc.query(sql, (row, index) -> {
            try {
                return new Entry(row.getLong("sequence_id"),
                        mapper.readValue(row.getString("bundle_json"), SourceProcessingConfigEvent.class));
            } catch (Exception failure) { throw new IllegalStateException("Invalid stored processing bundle", failure); }
        }, arguments.toArray());
    }

    @GetMapping("/internal/device-variable-runtime-access")
    public java.util.Map<String, Boolean> active(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @RequestParam("deviceId") UUID device, @RequestParam("sourceId") UUID source,
            @RequestParam("variableId") UUID variable) {
        auth.require(token);
        Integer count = jdbc.queryForObject("""
                select count(*) from source_variable_bindings b
                join data_sources s on s.id=b.data_source_id join devices d on d.id=s.device_id
                join accounts a on a.id=d.account_id join device_variables v on v.id=b.variable_id
                where d.id=? and s.id=? and v.id=? and a.status='ACTIVE' and d.status='ACTIVE'
                and s.enabled=true and s.status='ACTIVE' and b.enabled=true and v.archived=false
                """, Integer.class, device, source, variable);
        return java.util.Map.of("enabled", count > 0);
    }

    @PostMapping("/internal/device-variable-runtime-access")
    public List<RuntimeTarget> activeBatch(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @RequestBody RuntimeRequest request) {
        auth.require(token);
        if (request.deviceId() == null || request.targets() == null || request.targets().size() > 500
                || request.targets().stream().anyMatch(target -> target == null || target.dataSourceId() == null || target.variableId() == null)) {
            throw new IllegalArgumentException("Invalid variable capability batch");
        }
        if (request.targets().isEmpty()) return List.of();
        String alternatives = String.join(" or ", java.util.Collections.nCopies(request.targets().size(), "(s.id=? and v.id=?)"));
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(request.deviceId());
        request.targets().forEach(target -> { arguments.add(target.dataSourceId()); arguments.add(target.variableId()); });
        return jdbc.query("""
                select s.id source_id,v.id variable_id from source_variable_bindings b
                join data_sources s on s.id=b.data_source_id join devices d on d.id=s.device_id
                join accounts a on a.id=d.account_id join device_variables v on v.id=b.variable_id
                where d.id=? and a.status='ACTIVE' and d.status='ACTIVE' and s.enabled=true
                and s.status='ACTIVE' and b.enabled=true and v.archived=false and (
                """ + alternatives + ")", (row, index) -> new RuntimeTarget(row.getObject("source_id", UUID.class),
                row.getObject("variable_id", UUID.class)), arguments.toArray());
    }
}
