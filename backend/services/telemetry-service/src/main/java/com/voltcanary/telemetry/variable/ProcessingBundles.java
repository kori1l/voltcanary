package com.voltcanary.telemetry.variable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.ProcessingContext;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProcessingBundles {
    private final com.voltcanary.common.web.JdbcDeviceFence fence;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProcessingBundles(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.fence = new com.voltcanary.common.web.JdbcDeviceFence(jdbc);
        this.mapper = mapper;
    }

    @Transactional
    public void apply(SourceProcessingConfigEvent event) {
        if (event == null || event.dataSourceId() == null || event.accountId() == null || event.deviceId() == null
                || event.generation() < 1 || event.digest() == null || !event.digest().matches("[a-f0-9]{64}")
                || event.bindings().size() > 32) throw new IllegalArgumentException("Invalid processing bundle");
        if (!fence.writable(event.deviceId())) return;
        var existing = jdbc.query("select digest from variable_processing_bundles where source_id=? and generation=?",
                (row, index) -> row.getString(1), event.dataSourceId(), event.generation());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(event.digest())) throw new IllegalArgumentException("Conflicting processing digest");
            return;
        }
        if (jdbc.queryForObject("select count(*) from variable_source_locks where source_id=?", Integer.class,
                event.dataSourceId()) == 0) {
            jdbc.update("insert into variable_source_locks(source_id) values(?)", event.dataSourceId());
        }
        jdbc.queryForObject("select source_id from variable_source_locks where source_id=? for update", java.util.UUID.class,
                event.dataSourceId());
        jdbc.update("""
                insert into variable_processing_bundles(source_id,generation,digest,account_id,device_id,bundle_json)
                values(?,?,?,?,?,?)
                """, event.dataSourceId(), event.generation(), event.digest(), event.accountId(), event.deviceId(), json(event));
        projectCatalogue(event);
    }

    private void projectCatalogue(SourceProcessingConfigEvent event) {
        long latest = jdbc.queryForObject("select max(generation) from variable_processing_bundles where source_id=?",
                Long.class, event.dataSourceId());
        if (latest != event.generation()) return;
        jdbc.update("update variable_series_catalog set enabled=false,generation=? where data_source_id=?",
                event.generation(), event.dataSourceId());
        for (var binding : event.bindings()) {
            var contract = binding.contract();
            jdbc.update("delete from variable_series_catalog where data_source_id=? and variable_id=?",
                    event.dataSourceId(), contract.variableId());
            jdbc.update("""
                    insert into variable_series_catalog
                    (data_source_id,variable_id,account_id,device_id,external_source_id,variable_key,label,origin,unit,
                     revision,generation,enabled,metric_token) values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, event.dataSourceId(), contract.variableId(), event.accountId(), event.deviceId(), event.sourceId(),
                    contract.key(), contract.label(), contract.origin(), contract.unit(), contract.revision(),
                    event.generation(), event.enabled(), "var_" + contract.variableId().toString().replace("-", ""));
        }
    }

    public Optional<SourceProcessingConfigEvent> find(ProcessingContext context) {
        return jdbc.query("select bundle_json,digest from variable_processing_bundles where source_id=? and generation=?",
                (row, index) -> {
                    if (!context.digest().equals(row.getString("digest"))) throw new IllegalArgumentException("Processing digest mismatch");
                    return decode(row.getString("bundle_json"));
                }, context.dataSourceId(), context.generation()).stream().findFirst();
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode processing data", failure); }
    }

    private SourceProcessingConfigEvent decode(String json) {
        try { return mapper.readValue(json, SourceProcessingConfigEvent.class); }
        catch (Exception failure) { throw new IllegalStateException("Invalid persisted processing bundle", failure); }
    }
}
