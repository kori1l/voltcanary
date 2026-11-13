package com.voltcanary.alert.configurable.application;

import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.Set;
import java.util.UUID;

public final class EvidencePolicyRequests {
    public record Reservation(UUID grantId, long epoch, String key) { }
    public record Preparation(UUID reservationId, EvidencePolicyDefinition definition) { }
    private static final VclParser PARSER = new VclParser();
    private EvidencePolicyRequests() { }

    public static Reservation reserve(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("kind", "grantId", "expectedGrantEpoch", "idempotencyKey"));
        if (!"EVIDENCE_POLICY".equals(value.path("kind").textValue())) throw new VclException("REQUEST_FIELDS_INVALID", "");
        var epochAndKey = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        epochAndKey.set("expectedEpoch", value.path("expectedGrantEpoch"));
        epochAndKey.set("idempotencyKey", value.path("idempotencyKey"));
        var checked = AutomationGrantRequests.revoke(epochAndKey.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Reservation(AutomationGrantScope.uuid(value.path("grantId")), checked.epoch(), checked.key());
    }

    public static Preparation prepare(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("reservationId", "definition"));
        return new Preparation(AutomationGrantScope.uuid(value.path("reservationId")), EvidencePolicyDefinition.parse(value.path("definition")));
    }
}
