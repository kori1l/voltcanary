package com.voltcanary.alert.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclException;
import java.util.Set;

/** Reviewed metadata only: private references do not authorize evidence values, sharing or delivery. */
public record EvidencePolicyDefinition(int retentionDays, int maxObservationReferences) {
    public static final String PROFILE = com.voltcanary.vcl.VclEvidencePolicyDescription.PROFILE;
    public EvidencePolicyDefinition {
        if (retentionDays < 1 || retentionDays > 30 || maxObservationReferences < 1 || maxObservationReferences > 100) {
            throw new VclException("EVIDENCE_POLICY_LIMIT_INVALID", "/definition");
        }
    }

    public static EvidencePolicyDefinition parse(JsonNode value) {
        AutomationGrantScope.fields(value, Set.of("mode", "retentionDays", "maxObservationReferences"));
        if (!"PRIVATE_REFERENCES".equals(value.path("mode").textValue()) || !integer(value.path("retentionDays"))
                || !integer(value.path("maxObservationReferences"))) throw new VclException("EVIDENCE_POLICY_LIMIT_INVALID", "/definition");
        return new EvidencePolicyDefinition(value.path("retentionDays").intValue(), value.path("maxObservationReferences").intValue());
    }

    public JsonNode json() {
        return JsonNodeFactory.instance.objectNode().put("mode", "PRIVATE_REFERENCES")
                .put("retentionDays", retentionDays).put("maxObservationReferences", maxObservationReferences);
    }

    private static boolean integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }
}
