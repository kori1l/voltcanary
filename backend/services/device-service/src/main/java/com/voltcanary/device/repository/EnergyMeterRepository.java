package com.voltcanary.device.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.common.web.EnergyMeterDefinition.CounterEvent;
import com.voltcanary.device.dto.EnergyMeterRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EnergyMeterRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EnergyMeterRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean lockAccount(UUID accountId) {
        return !jdbc.query("SELECT id FROM accounts WHERE id=? AND status='ACTIVE' FOR UPDATE",(row,index)->row.getObject("id",UUID.class),accountId).isEmpty();
    }

    public boolean sourceOwned(UUID accountId,UUID deviceId,UUID sourceId) {
        Long count=jdbc.queryForObject("SELECT count(*) FROM data_sources s JOIN devices d ON d.id=s.device_id WHERE s.id=? AND s.device_id=? AND d.account_id=?",Long.class,sourceId,deviceId,accountId);
        return count!=null && count==1;
    }

    public Map<UUID,List<EnergyMeterDefinition>> definitions(UUID accountId,List<UUID> deviceIds) {
        Map<UUID,List<EnergyMeterDefinition>> definitions = new HashMap<>();
        if (deviceIds.isEmpty()) return definitions;
        List<Object> args = new ArrayList<>();
        args.add(accountId);
        args.addAll(deviceIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(deviceIds.size(), "?"));
        jdbc.query("SELECT * FROM energy_meter_revisions WHERE account_id=? AND device_id IN ("+placeholders+") ORDER BY device_id,revision",
                row -> { definitions.computeIfAbsent(row.getObject("device_id",UUID.class), ignored -> new ArrayList<>()).add(map(row)); },args.toArray());
        Map<UUID,List<UUID>> parents = new HashMap<>();
        jdbc.query("SELECT DISTINCT physical_meter_id,parent_physical_meter_id FROM energy_meter_revisions WHERE account_id=?",row -> {
            UUID physical=row.getObject("physical_meter_id",UUID.class);
            UUID parent=row.getObject("parent_physical_meter_id",UUID.class);
            List<UUID> edges=parents.computeIfAbsent(physical,ignored->new ArrayList<>());
            if (parent!=null) edges.add(parent);
        },accountId);
        definitions.replaceAll((device,history) -> history.stream().map(meter -> withAncestors(meter,parents)).toList());
        return definitions;
    }

    public List<EnergyMeterDefinition> current(UUID accountId) {
        return jdbc.query("SELECT * FROM energy_meter_revisions WHERE account_id=? AND effective_to IS NULL",
                (row,index) -> map(row),accountId);
    }

    public EnergyMeterDefinition append(UUID accountId,UUID deviceId,EnergyMeterRequest request,long revision) {
        jdbc.update("UPDATE energy_meter_revisions SET effective_to=?,active_marker=NULL WHERE account_id=? AND device_id=? AND effective_to IS NULL",
                Timestamp.from(request.effectiveFrom()),accountId,deviceId);
        List<CounterEvent> events = request.counterEvents() == null ? List.of() : request.counterEvents();
        String json;
        try {
            json = mapper.writeValueAsString(events);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid counter metadata",exception);
        }
        jdbc.update("""
                INSERT INTO energy_meter_revisions(id,account_id,device_id,revision,physical_meter_id,
                parent_physical_meter_id,source_id,authoritative_method,max_gap_seconds,effective_from,counter_events)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),accountId,deviceId,revision,request.physicalMeterId(),request.parentPhysicalMeterId(),
                request.sourceId(),request.authoritativeMethod(),request.maxGapSeconds(),Timestamp.from(request.effectiveFrom()),json);
        jdbc.update("UPDATE devices SET updated_at=? WHERE id=? AND account_id=?",Timestamp.from(Instant.now()),deviceId,accountId);
        return new EnergyMeterDefinition(revision,request.physicalMeterId(),request.parentPhysicalMeterId(),request.sourceId(),
                request.authoritativeMethod(),request.maxGapSeconds(),request.effectiveFrom(),null,events);
    }

    private EnergyMeterDefinition withAncestors(EnergyMeterDefinition meter,Map<UUID,List<UUID>> parents) {
        Set<UUID> ancestors = new HashSet<>();
        boolean complete = meter.parentPhysicalMeterId()==null || ancestorClosure(meter.parentPhysicalMeterId(),parents,
                new HashSet<>(List.of(meter.physicalMeterId())),ancestors,new HashSet<>());
        return new EnergyMeterDefinition(meter.revision(),meter.physicalMeterId(),meter.parentPhysicalMeterId(),
                meter.sourceId(),meter.authoritativeMethod(),meter.maxGapSeconds(),meter.effectiveFrom(),meter.effectiveTo(),
                meter.counterEvents(),ancestors.stream().sorted().toList(),complete);
    }

    private boolean ancestorClosure(UUID id,Map<UUID,List<UUID>> parents,Set<UUID> path,Set<UUID> ancestors,Set<UUID> done) {
        if (path.contains(id) || ancestors.size()>=32 || !parents.containsKey(id)) return false;
        ancestors.add(id);
        if (done.contains(id)) return true;
        path.add(id);
        for (UUID parent:parents.get(id)) if (!ancestorClosure(parent,parents,path,ancestors,done)) return false;
        path.remove(id);
        done.add(id);
        return true;
    }

    private EnergyMeterDefinition map(ResultSet row) throws SQLException {
        List<CounterEvent> events;
        try {
            events = mapper.readValue(row.getString("counter_events"),new TypeReference<List<CounterEvent>>() {});
        } catch (java.io.IOException exception) {
            throw new SQLException("Invalid stored counter metadata",exception);
        }
        Timestamp until = row.getTimestamp("effective_to");
        return new EnergyMeterDefinition(row.getLong("revision"),row.getObject("physical_meter_id",UUID.class),
                row.getObject("parent_physical_meter_id",UUID.class),row.getString("source_id"),row.getString("authoritative_method"),
                row.getLong("max_gap_seconds"),row.getTimestamp("effective_from").toInstant(),until==null?null:until.toInstant(),events);
    }
}
