package com.voltcanary.device.variable.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.variable.domain.VariableDefinition;
import com.voltcanary.device.variable.domain.VariableRevision;
import com.voltcanary.device.variable.port.VariableStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class JdbcVariableStore implements VariableStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final jakarta.persistence.EntityManager entities;

    public JdbcVariableStore(JdbcTemplate jdbc, ObjectMapper mapper, jakarta.persistence.EntityManager entities) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.entities = entities;
    }

    public void lockAccount(UUID accountId) {
        entities.flush();
        jdbc.queryForObject("select id from accounts where id=? for update", UUID.class, accountId);
    }

    public VariableDefinition get(UUID accountId, String type, UUID id) {
        return jdbc.query("select * from device_variables where account_id=? and device_type=? and id=?",
                this::definition, accountId, type, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Variable not found"));
    }

    public List<VariableDefinition> list(UUID accountId, String type) {
        return jdbc.query("select * from device_variables where account_id=? and device_type=? order by variable_key",
                this::definition, accountId, type);
    }

    public void insert(VariableDefinition definition, UUID actor) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into device_variables
                (id,account_id,device_type,variable_key,label,origin,unit,version,archived,
                 created_by,updated_by,created_at,updated_at)
                values(?,?,?,?,?,?,?,1,false,?,?,?,?)
                """, definition.id(), definition.accountId(), definition.deviceType(), definition.key(),
                definition.label(), definition.origin(), definition.unit(), actor, actor, now, now);
    }

    public void advance(UUID id, int expectedVersion, UUID actor, boolean archive, Integer defaultRevision) {
        int changed = jdbc.update("""
                update device_variables set version=version+1,archived=?,default_revision=?,
                updated_by=?,updated_at=? where id=? and version=?
                """, archive, defaultRevision, actor, Timestamp.from(Instant.now()), id, expectedVersion);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable changed; reload");
    }

    public void insertRevision(UUID id, VariableRevision revision, UUID actor) {
        jdbc.update("""
                insert into device_variable_revisions
                (variable_id,revision,state,contract_json,digest,created_by,created_at) values(?,?,?,?,?,?,?)
                """, id, revision.revision(), revision.state(), json(revision.contract()), revision.digest(),
                actor, Timestamp.from(Instant.now()));
    }

    public VariableRevision revision(UUID id, int revision) {
        return jdbc.query("select * from device_variable_revisions where variable_id=? and revision=?",
                this::revisionRow, id, revision).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Variable revision not found"));
    }

    public List<VariableRevision> revisions(UUID id) {
        return jdbc.query("select * from device_variable_revisions where variable_id=? order by revision",
                this::revisionRow, id);
    }

    public void publish(UUID id, int revision, UUID actor) {
        int changed = jdbc.update("update device_variable_revisions set state='PUBLISHED',published_by=?,published_at=? where variable_id=? and revision=? and state='DRAFT'",
                actor, Timestamp.from(Instant.now()), id, revision);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Revision already published");
    }

    public void rename(UUID id, String label) {
        jdbc.update("update device_variables set label=? where id=?", label, id);
    }

    private VariableDefinition definition(ResultSet row, int index) throws SQLException {
        return new VariableDefinition(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
                row.getString("device_type"), row.getString("variable_key"), row.getString("label"),
                row.getString("origin"), row.getString("unit"), row.getInt("version"),
                row.getBoolean("archived"), row.getObject("default_revision", Integer.class));
    }

    private VariableRevision revisionRow(ResultSet row, int index) throws SQLException {
        try {
            return new VariableRevision(row.getInt("revision"), row.getString("state"), row.getString("digest"),
                    mapper.readValue(row.getString("contract_json"), VariableContract.class));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored variable contract", failure);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid contract", failure); }
    }
}
