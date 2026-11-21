package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.variable.domain.VariableDefinition;
import com.voltcanary.device.variable.domain.VariableRevision;
import com.voltcanary.device.variable.domain.VariableValidation;
import com.voltcanary.device.variable.port.VariableStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class VariableDefinitions {
    public record Draft(String key, String label, String origin, String unit,
            Map<String, VariableContract.Input> inputs, VariableExpression expression,
            Double minimum, Double maximum, Integer expectedVersion) { }
    public record Publication(int expectedVersion, int revision, boolean makeDefault) { }
    public record Detail(VariableDefinition definition, List<VariableRevision> revisions) { }

    private final VariableStore store;
    private final AccountService accounts;
    private final DeviceTypeRepository types;
    private final ObjectMapper mapper;
    private final org.springframework.context.ApplicationEventPublisher events;

    public List<VariableDefinition> list(AuthenticatedUser user, String type) {
        requireType(type);
        return store.list(accounts.currentAccount(user).getId(), type);
    }

    public Detail detail(AuthenticatedUser user, String type, UUID id) {
        VariableDefinition definition = owned(user, type, id);
        return new Detail(definition, store.revisions(id));
    }

    public VariableDefinition owned(AuthenticatedUser user, String type, UUID id) {
        return store.get(accounts.currentAccount(user).getId(), type, id);
    }

    @Transactional
    public Detail create(AuthenticatedUser user, String type, Draft draft) {
        requireType(type);
        UUID account = accounts.currentAccount(user).getId();
        store.lockAccount(account);
        if (store.list(account, type).size() >= 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 100 variables per type");
        }
        UUID id = UUID.randomUUID();
        VariableContract contract = contract(id, 1, draft);
        VariableValidation.contract(contract);
        VariableDefinition definition = new VariableDefinition(id, account, type, contract.key(),
                contract.label(), contract.origin(), contract.unit(), 1, false, null);
        try { store.insert(definition, user.id()); }
        catch (DuplicateKeyException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable key already exists");
        }
        store.insertRevision(id, new VariableRevision(1, "DRAFT", digest(contract), contract), user.id());
        return detail(user, type, id);
    }

    @Transactional
    public Detail revise(AuthenticatedUser user, String type, UUID id, Draft draft) {
        VariableDefinition definition = locked(user, type, id);
        requireMutable(definition);
        requireVersion(definition, draft.expectedVersion());
        if (!definition.key().equals(draft.key()) || !definition.origin().equals(draft.origin())
                || !definition.unit().equals(draft.unit())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Key, origin and unit require a new definition");
        }
        int revision = store.revisions(id).size() + 1;
        if (revision > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revision limit exceeded");
        VariableContract contract = contract(id, revision, draft);
        VariableValidation.contract(contract);
        store.advance(id, definition.version(), user.id(), false, definition.defaultRevision());
        store.insertRevision(id, new VariableRevision(revision, "DRAFT", digest(contract), contract), user.id());
        store.rename(id, contract.label());
        return detail(user, type, id);
    }

    @Transactional
    public Detail publish(AuthenticatedUser user, String type, UUID id, Publication request) {
        VariableDefinition definition = locked(user, type, id);
        requireMutable(definition);
        requireVersion(definition, request.expectedVersion());
        VariableRevision revision = store.revision(id, request.revision());
        VariableValidation.contract(revision.contract());
        store.publish(id, request.revision(), user.id());
        store.advance(id, definition.version(), user.id(), false,
                request.makeDefault() ? Integer.valueOf(request.revision()) : definition.defaultRevision());
        return detail(user, type, id);
    }

    @Transactional
    public VariableDefinition archive(AuthenticatedUser user, String type, UUID id, int expectedVersion) {
        VariableDefinition definition = locked(user, type, id);
        requireMutable(definition);
        requireVersion(definition, expectedVersion);
        store.advance(id, expectedVersion, user.id(), true, null);
        events.publishEvent(new Archived(id, user.id()));
        return owned(user, type, id);
    }

    private VariableDefinition locked(AuthenticatedUser user, String type, UUID id) {
        UUID account = accounts.currentAccount(user).getId();
        store.lockAccount(account);
        return store.get(account, type, id);
    }

    public record Archived(UUID variableId, UUID actor) { }

    private void requireType(String type) {
        if (type == null || !types.findByCode(type).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device type not found");
        }
    }

    private void requireMutable(VariableDefinition definition) {
        if (definition.archived()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable archived");
    }

    private void requireVersion(VariableDefinition definition, Integer expected) {
        if (expected == null || expected != definition.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variable changed; reload");
        }
    }

    private VariableContract contract(UUID id, int revision, Draft draft) {
        if (draft == null) throw new IllegalArgumentException("Draft is required");
        return new VariableContract(id, revision, draft.key(), draft.label(), draft.origin(), draft.unit(),
                "v1", draft.inputs(), draft.expression(), draft.minimum(), draft.maximum());
    }

    private String digest(Object value) {
        try {
            byte[] bytes = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot hash variable contract", failure);
        }
    }
}
