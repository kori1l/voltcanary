package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DashboardRequestDecoder {
    private final ObjectMapper mapper;
    private final DefinitionValidation validation;

    public DashboardRequestDecoder(ObjectMapper mapper, DefinitionValidation validation) {
        this.mapper = mapper;
        this.validation = validation;
    }

    public DashboardDefinition definition(JsonNode body) {
        validation.validatePayload(body);
        try {
            return validation.validate(mapper.convertValue(body.path("definition"), DashboardDefinition.class));
        } catch (IllegalArgumentException exception) {
            throw new DashboardFailure("VALIDATION_ERROR", "Invalid definition structure", "definition");
        }
    }

    public void validateRequest(JsonNode body) {
        validation.validatePayload(body);
    }

    public UUID uuid(JsonNode body, String field, boolean optional) {
        JsonNode value = body.get(field);
        if (optional && (value == null || value.isNull())) {
            return null;
        }
        try {
            if (value == null || !value.isTextual() || value.asText().length() != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw new DashboardFailure("VALIDATION_ERROR", "Expected a UUID", field);
        }
    }

    public int nonnegative(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw new DashboardFailure("VALIDATION_ERROR", "Expected a nonnegative integer", field);
        }
        return value.asInt();
    }
}
