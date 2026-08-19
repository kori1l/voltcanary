package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MlModelApprovalGateVerifier {

    private final ObjectMapper objectMapper;

    public MlModelApprovalGateVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void verify(MlModelVersion model) {
        verifyArtifactMetadata(model);
        verifyFailureApprovalGates(model);
    }

    private void verifyArtifactMetadata(MlModelVersion model) {
        if (model.getArtifactUri() == null || model.getArtifactUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact URI is required before approval");
        }
        if (model.getArtifactManifestUri() == null || model.getArtifactManifestUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact manifest URI is required before approval");
        }
        if (model.getArtifactHash() == null || !model.getArtifactHash().startsWith("sha256:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact hash must be a sha256 value before approval");
        }
        if (model.getArtifactSizeBytes() == null || model.getArtifactSizeBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact size must be recorded before approval");
        }
    }

    private void verifyFailureApprovalGates(MlModelVersion model) {
        if (model.getFamily() != MlModelFamily.FAILURE_PREDICTION) {
            return;
        }
        Map<String, Object> metrics = readJsonObject(model.getEvaluationMetricsJson());
        Object approval = metrics.get("approvalGates");
        if (!(approval instanceof Map<?, ?> gates)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval gates are missing; retrain or repair the model with auditable gate metadata");
        }
        Object profileId = gates.get("profileId");
        Object profileVersion = gates.get("profileVersion");
        Object thresholdSource = gates.get("thresholdSource");
        if (isBlank(profileId) || isBlank(profileVersion) || isBlank(thresholdSource)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval gate profile metadata is missing; retrain or repair the model with auditable gate metadata");
        }
        Object criteria = gates.get("criteria");
        if (!(criteria instanceof List<?> items) || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure model approval gates are empty");
        }
        List<String> failed = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> gate)) {
                continue;
            }
            boolean passed = Boolean.TRUE.equals(gate.get("passed"));
            if (!passed) {
                Object rawName = gate.get("name");
                Object rawReason = gate.get("reason");
                String name = rawName == null ? "approval_gate" : String.valueOf(rawName);
                String reason = rawReason == null ? "Approval gate failed" : String.valueOf(rawReason);
                failed.add(name + ": " + reason);
            }
        }
        if (!failed.isEmpty() || !Boolean.TRUE.equals(gates.get("promotionEligible"))) {
            String source = String.valueOf(thresholdSource);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval blocked by " + source + " gates: " + String.join("; ", failed));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse ML model evaluation metrics");
        }
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
