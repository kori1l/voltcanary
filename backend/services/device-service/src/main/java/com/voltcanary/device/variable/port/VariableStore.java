package com.voltcanary.device.variable.port;

import com.voltcanary.device.variable.domain.VariableDefinition;
import com.voltcanary.device.variable.domain.VariableRevision;
import java.util.List;
import java.util.UUID;

public interface VariableStore {
    void lockAccount(UUID accountId);
    VariableDefinition get(UUID accountId, String type, UUID id);
    List<VariableDefinition> list(UUID accountId, String type);
    void insert(VariableDefinition definition, UUID actor);
    void advance(UUID id, int expectedVersion, UUID actor, boolean archive, Integer defaultRevision);
    void insertRevision(UUID id, VariableRevision revision, UUID actor);
    VariableRevision revision(UUID id, int revision);
    List<VariableRevision> revisions(UUID id);
    void publish(UUID id, int revision, UUID actor);
    void rename(UUID id, String label);
}
