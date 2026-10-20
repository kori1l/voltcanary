package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.telemetry.SourceRuntimeJson;
import com.voltcanary.device.variable.adapter.VariableBindingRows;
import com.voltcanary.device.variable.domain.VariableBinding;
import com.voltcanary.device.variable.domain.VariableDefinition;
import com.voltcanary.device.variable.port.VariableStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VariableBindings {
    public record Edit(UUID variableId, int revision, int expectedVersion,
            Map<String, String> inputPaths, boolean enabled) { }

    private final VariableStore variables;
    private final AccountService accounts;
    private final VariableBindingRows rows;
    private final VariableRetentionGuard retention;
    private final VariableBundles bundles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final com.voltcanary.device.config.VariableDefinitionProperties properties;

    public VariableBindings(VariableStore variables, AccountService accounts, VariableBindingRows rows,
            VariableRetentionGuard retention, VariableBundles bundles, JdbcTemplate jdbc, ObjectMapper mapper,
            com.voltcanary.device.config.VariableDefinitionProperties properties) {
        this.variables = variables;
        this.accounts = accounts;
        this.rows = rows;
        this.retention = retention;
        this.bundles = bundles;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<VariableBinding> list(AuthenticatedUser user, UUID device, UUID source) {
        rows.source(accounts.currentAccount(user).getId(), device, source, false);
        return rows.bindings(source);
    }

    @Transactional
    public List<VariableBinding> save(AuthenticatedUser user, UUID device, UUID source, Edit edit) {
        UUID account = accounts.currentAccount(user).getId();
        variables.lockAccount(account);
        VariableBindingRows.Source context = rows.source(account, device, source, true);
        apply(user.id(), context, edit, false);
        return rows.bindings(source);
    }

    public Map<String, Object> status(AuthenticatedUser user, UUID device, UUID source) {
        rows.source(accounts.currentAccount(user).getId(), device, source, false);
        Long generation = jdbc.queryForObject("select max(generation) from source_processing_bundles where data_source_id=?",
                Long.class, source);
        return Map.of("desiredGeneration", generation == null ? 0L : generation, "bindings", rows.bindings(source));
    }

    @Transactional
    public void inherit(AuthenticatedUser user, UUID device, UUID source, Edit edit) {
        UUID account = accounts.currentAccount(user).getId();
        variables.lockAccount(account);
        var context = rows.source(account, device, source, true);
        var definition = variables.get(account, context.deviceType(), edit.variableId());
        var defaults = jdbc.queryForList("select * from device_variable_default_bindings where variable_id=?", edit.variableId());
        if (!java.util.Objects.equals(definition.defaultRevision(), edit.revision()) || defaults.isEmpty()
                || !context.sourceType().equals(defaults.getFirst().get("source_type"))
                || !edit.inputPaths().equals(rows.paths(defaults.getFirst().get("input_paths_json").toString()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Default binding changed; retry reconciliation");
        }
        apply(user.id(), context, edit, true);
    }

    public void apply(UUID actor, VariableBindingRows.Source source, Edit edit, boolean inherited) {
        if (edit == null || edit.variableId() == null || edit.inputPaths() == null) {
            throw new IllegalArgumentException("Variable binding is required");
        }
        if (edit.enabled() && !properties.isActivationEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Variable processing is not enabled for activation");
        }
        VariableDefinition definition = variables.get(source.accountId(), source.deviceType(), edit.variableId());
        var revision = variables.revision(definition.id(), edit.revision());
        if (edit.enabled() && (definition.archived() || !"PUBLISHED".equals(revision.state()) || !source.active())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Binding needs a published variable and active source");
        }
        VariableBinding previous = rows.bindings(source.sourceId()).stream()
                .filter(binding -> binding.variableId().equals(edit.variableId())).findFirst().orElse(null);
        int currentVersion = previous == null ? 0 : previous.version();
        if (edit.expectedVersion() != currentVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Binding changed; reload");
        }
        if (!revision.contract().inputs().keySet().equals(edit.inputPaths().keySet())) {
            throw new IllegalArgumentException("Input aliases must match the published revision");
        }
        var policy = retention.policy(SourceRuntimeJson.objectMap(mapper, source.mappingJson()));
        for (var entry : edit.inputPaths().entrySet()) {
            if (entry.getValue() == null || !entry.getValue().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,3}")) {
                throw new IllegalArgumentException("Invalid input path");
            }
            if (edit.enabled() && !retention.retained(policy, entry.getValue())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Retain numeric input " + entry.getValue() + " before activation");
            }
            if (edit.enabled() && policy != null) {
                String inputUnit = revision.contract().inputs().get(entry.getKey()).unit();
                boolean compatible = policy.fields().stream().anyMatch(field -> field.path().equals(entry.getValue())
                        && field.unit().equals(inputUnit));
                if (!compatible) throw new IllegalArgumentException("Input unit differs from retained field metadata");
            }
        }
        if (edit.enabled() && (previous == null || !previous.enabled()) && rows.bindings(source.sourceId()).stream()
                .filter(VariableBinding::enabled).count() >= 32) {
            throw new IllegalArgumentException("At most 32 enabled variables per source");
        }
        Timestamp now = Timestamp.from(Instant.now());
        if (previous == null) {
            jdbc.update("""
                    insert into source_variable_bindings
                    (data_source_id,variable_id,revision,version,input_paths_json,enabled,inherited,updated_by,updated_at)
                    values(?,?,?,1,?,?,?,?,?)
                    """, source.sourceId(), definition.id(), edit.revision(), json(edit.inputPaths()),
                    edit.enabled(), inherited, actor, now);
        } else {
            jdbc.update("""
                    update source_variable_bindings set revision=?,version=version+1,input_paths_json=?,
                    enabled=?,inherited=?,updated_by=?,updated_at=? where data_source_id=? and variable_id=?
                    """, edit.revision(), json(edit.inputPaths()), edit.enabled(), inherited, actor, now,
                    source.sourceId(), definition.id());
        }
        bundles.rebuild(source);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid binding", failure); }
    }
}
