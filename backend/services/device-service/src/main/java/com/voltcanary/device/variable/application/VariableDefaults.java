package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.variable.adapter.VariableBindingRows;
import com.voltcanary.device.variable.port.VariableStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class VariableDefaults {
    public record Edit(int expectedVersion, int revision, String sourceType, Map<String, String> inputPaths) { }
    public record Outcome(UUID deviceId, String status, String diagnostic) { }
    private final VariableStore variables;
    private final AccountService accounts;
    private final VariableBindingRows rows;
    private final VariableBindings bindings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional
    public void configure(AuthenticatedUser user, String type, UUID variable, Edit edit) {
        UUID account = accounts.currentAccount(user).getId();
        variables.lockAccount(account);
        var definition = variables.get(account, type, variable);
        if (definition.archived() || definition.version() != edit.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable changed or archived");
        }
        DataSourceType.valueOf(edit.sourceType());
        var revision = variables.revision(variable, edit.revision());
        if (!"PUBLISHED".equals(revision.state()) || edit.inputPaths() == null
                || !revision.contract().inputs().keySet().equals(edit.inputPaths().keySet())) {
            throw new IllegalArgumentException("Default requires published revision and exact input aliases");
        }
        variables.advance(variable, definition.version(), user.id(), false, edit.revision());
        jdbc.update("delete from device_variable_default_bindings where variable_id=?", variable);
        jdbc.update("""
                insert into device_variable_default_bindings
                (variable_id,source_type,input_paths_json,created_by) values(?,?,?,?)
                """, variable, edit.sourceType(), json(edit.inputPaths()), user.id());
    }

    /** Each inherited source is independently transactional; failures cannot partially activate it. */
    public List<Outcome> reconcile(AuthenticatedUser user, String type, UUID variable) {
        UUID account = accounts.currentAccount(user).getId();
        var definition = variables.get(account, type, variable);
        if (definition.archived()) return List.of();
        var defaults = jdbc.queryForList("select * from device_variable_default_bindings where variable_id=?", variable);
        if (defaults.isEmpty() || definition.defaultRevision() == null) return List.of();
        String role = defaults.getFirst().get("source_type").toString();
        Map<String, String> paths = rows.paths(defaults.getFirst().get("input_paths_json").toString());
        List<UUID> devices = jdbc.query("""
                select d.id from devices d join device_types t on t.id=d.device_type_id
                where d.account_id=? and t.code=? and d.status<>'DECOMMISSIONED'
                and not exists (select 1 from source_variable_bindings b join data_sources s
                    on s.id=b.data_source_id where b.variable_id=? and s.device_id=d.id)
                and cast(d.id as varchar)>? order by cast(d.id as varchar) limit 50
                """, (row, index) -> row.getObject(1, UUID.class), account, type, variable,
                defaults.getFirst().get("device_cursor").toString());
        var outcomes = devices.stream().map(device -> inherit(user, account, device, variable,
                definition.defaultRevision(), role, paths)).toList();
        jdbc.update("update device_variable_default_bindings set device_cursor=? where variable_id=?",
                devices.isEmpty() ? "" : devices.getLast().toString(), variable);
        return outcomes;
    }

    private Outcome inherit(AuthenticatedUser actor, UUID account, UUID device, UUID variable, int revision,
            String role, Map<String, String> paths) {
        List<UUID> candidates = jdbc.query("""
                select id from data_sources where device_id=? and source_type=?
                and enabled=true and status='ACTIVE' order by id
                """, (row, index) -> row.getObject(1, UUID.class), device, role);
        if (candidates.size() != 1) return new Outcome(device, "UNBOUND", "Select exactly one source of the configured role");
        // Validate before mutation; a policy mismatch is a visible unbound outcome.
        try {
            bindings.inherit(actor, device, candidates.getFirst(), new VariableBindings.Edit(variable, revision, 0, paths, true));
            return new Outcome(device, "PROPAGATING", null);
        } catch (IllegalArgumentException | ResponseStatusException failure) {
            // apply validates before writing; unexpected persistence failures are not swallowed.
            return new Outcome(device, "UNBOUND", failure.getMessage());
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid default binding", failure); }
    }
}
