package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.domain.DashboardOperation;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict transport decoding shared by every provider; no coercion or unknown technical fields. */
public final class DashboardOperationProtocol {
    public static final String VERSION = "dashboard-operations-v1";
    public static final String DEFAULT_PERIOD = "LAST_24_HOURS";
    public record Envelope(String protocol, List<DashboardOperation> operations) { }
    private DashboardOperationProtocol() { }

    public static Envelope parse(Map<String, Object> values, ObjectMapper mapper) {
        if (!Set.of("protocol", "operations").equals(values.keySet()) || !VERSION.equals(values.get("protocol"))) {
            throw invalid("Expected dashboard-operations-v1 envelope only");
        }
        try {
            var strict = mapper.copy().disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
            var envelope = strict.readerFor(Envelope.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .without(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                    .readValue(mapper.writeValueAsBytes(values));
            Envelope result = (Envelope) envelope;
            if (result.operations() == null || result.operations().isEmpty() || result.operations().size() > 24) {
                throw invalid("Choose 1–24 operations");
            }
            var ids = new HashSet<String>();
            for (var operation : result.operations()) {
                if (operation == null || operation.id() == null || !operation.id().matches("[A-Za-z][A-Za-z0-9_-]{0,39}")
                        || !ids.add(operation.id())) throw invalid("Invalid or duplicate operation ID");
            }
            if (result.operations().size() != 1 && result.operations().stream().anyMatch(operation ->
                    operation instanceof DashboardOperation.Restore || operation instanceof DashboardOperation.Keep)) {
                throw invalid("Keeping or restoring a review must be the only operation");
            }
            return result;
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw invalid("Invalid operation fields or types");
        }
    }

    static DashboardPreparationRejected invalid(String message) {
        return new DashboardPreparationRejected(message);
    }
}
