package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDeploymentAuditEvent;
import com.voltcanary.analytics.repository.MlDeploymentAuditEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MlDeploymentAuditWriter {

    private final MlDeploymentAuditEventRepository auditRepository;
    private final ObjectMapper objectMapper;

    public MlDeploymentAuditWriter(MlDeploymentAuditEventRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    public void audit(UUID accountId, UUID actorId, UUID modelVersionId, UUID policyId, String action, Map<String, Object> details) {
        MlDeploymentAuditEvent event = new MlDeploymentAuditEvent();
        event.setId(UUID.randomUUID());
        event.setAccountId(accountId);
        event.setModelVersionId(modelVersionId);
        event.setPolicyId(policyId);
        event.setAction(action);
        event.setDetailsJson(writeJson(details));
        event.setActorId(actorId);
        event.setCreatedAt(Instant.now());
        auditRepository.save(event);
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML registry audit details");
        }
    }
}
