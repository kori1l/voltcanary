package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.variable.adapter.VariableBindingRows;
import com.voltcanary.device.variable.port.VariableStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VariableRollouts {
    public record Target(UUID deviceId, UUID sourceId) { }
    public record Request(int revision, List<Target> targets, Map<String, String> inputPaths, boolean enabled) { }
    public record Result(UUID id, UUID sourceId, String status, String diagnostic) { }

    private final VariableStore variables;
    private final AccountService accounts;
    private final VariableBindings bindings;
    private final VariableBindingRows rows;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public VariableRollouts(VariableStore variables, AccountService accounts, VariableBindings bindings,
            VariableBindingRows rows, JdbcTemplate jdbc, ObjectMapper mapper,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.variables = variables;
        this.accounts = accounts;
        this.bindings = bindings;
        this.rows = rows;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<Result> create(AuthenticatedUser user, String type, UUID variable, Request request) {
        return transactions.execute(status -> {
            UUID account = accounts.currentAccount(user).getId();
            variables.lockAccount(account);
            var definition = variables.get(account, type, variable);
            if (definition.archived() && request.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable archived");
            if (request.targets() == null || request.targets().isEmpty() || request.targets().size() > 50
                    || request.targets().stream().distinct().count() != request.targets().size()) {
                throw new IllegalArgumentException("Select one to fifty distinct sources per rollout batch");
            }
            var revision = variables.revision(variable, request.revision());
            if (!"PUBLISHED".equals(revision.state()) || request.inputPaths() == null
                    || !revision.contract().inputs().keySet().equals(request.inputPaths().keySet())) {
                throw new IllegalArgumentException("Rollout requires published revision and input aliases");
            }
            // Authorize the complete batch before creating any intent.
            request.targets().forEach(target -> {
                var source = rows.source(account, target.deviceId(), target.sourceId(), false);
                if (!source.deviceType().equals(type)) throw new IllegalArgumentException("Device type mismatch");
            });
            return request.targets().stream().map(target -> {
                UUID id = UUID.randomUUID();
                int expected = rows.bindings(target.sourceId()).stream().filter(binding -> binding.variableId().equals(variable))
                        .mapToInt(binding -> binding.version()).findFirst().orElse(0);
                jdbc.update("""
                        insert into variable_rollout_targets
                        (id,variable_id,revision,data_source_id,input_paths_json,enabled,status,created_by,updated_at,expected_binding_version)
                        values(?,?,?,?,?,?,'PENDING',?,?,?)
                        """, id, variable, request.revision(), target.sourceId(), json(request.inputPaths()),
                        request.enabled(), user.id(), Timestamp.from(Instant.now()), expected);
                return new Result(id, target.sourceId(), "PENDING", null);
            }).toList();
        });
    }

    public Result resume(AuthenticatedUser user, String type, UUID variable, UUID targetId) {
        UUID account = accounts.currentAccount(user).getId();
        variables.get(account, type, variable);
        var target = jdbc.queryForList("""
                select r.*,s.device_id from variable_rollout_targets r join data_sources s on s.id=r.data_source_id
                where r.id=? and r.variable_id=?
                """, targetId, variable).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rollout target not found"));
        UUID source = (UUID) target.get("data_source_id");
        UUID device = (UUID) target.get("device_id");
        try {
            return transactions.execute(status -> {
                jdbc.queryForObject("select id from variable_rollout_targets where id=? for update", UUID.class, targetId);
                String state = jdbc.queryForObject("select status from variable_rollout_targets where id=?", String.class, targetId);
                if ("APPLIED".equals(state)) return new Result(targetId, source, state, null);
                int version = ((Number) target.get("expected_binding_version")).intValue();
                bindings.save(user, device, source, new VariableBindings.Edit(variable,
                        ((Number) target.get("revision")).intValue(), version,
                        rows.paths(target.get("input_paths_json").toString()), (Boolean) target.get("enabled")));
                update(targetId, "APPLIED", null);
                return new Result(targetId, source, "APPLIED", null);
            });
        } catch (IllegalArgumentException | ResponseStatusException failure) {
            String diagnostic = failure.getMessage();
            diagnostic = diagnostic == null ? "Binding rejected" : diagnostic.substring(0, Math.min(200, diagnostic.length()));
            String savedDiagnostic = diagnostic;
            transactions.executeWithoutResult(status -> update(targetId, "FAILED", savedDiagnostic));
            return new Result(targetId, source, "FAILED", diagnostic);
        }
    }

    public List<Result> list(AuthenticatedUser user, String type, UUID variable) {
        variables.get(accounts.currentAccount(user).getId(), type, variable);
        return jdbc.query("select id,data_source_id,status,diagnostic from variable_rollout_targets where variable_id=? order by updated_at desc,id limit 500",
                (row, index) -> new Result(row.getObject("id", UUID.class), row.getObject("data_source_id", UUID.class),
                row.getString("status"), row.getString("diagnostic")), variable);
    }

    private void update(UUID id, String status, String diagnostic) {
        jdbc.update("update variable_rollout_targets set status=?,diagnostic=?,updated_at=? where id=?",
                status, diagnostic, Timestamp.from(Instant.now()), id);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid rollout inputs", failure); }
    }
}
