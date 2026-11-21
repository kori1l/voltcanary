package com.voltcanary.dashboard.controller;

import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.templates.DashboardTaskTemplates;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardTemplateController {
    private final DashboardOwnership ownership;
    private final DashboardTaskTemplates templates;
    private final com.voltcanary.dashboard.templates.DashboardTemplatePreparation preparation;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.voltcanary.dashboard.application.DefinitionValidation validation;

    @GetMapping("/dashboard-templates")
    public List<DashboardTaskTemplates.Template> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ownership.resolveReadOnly(authorization);
        return templates.catalog();
    }

    @org.springframework.web.bind.annotation.PostMapping("/dashboard-templates/{id}/prepare")
    public com.voltcanary.dashboard.templates.DashboardTemplatePreparation.Prepared prepare(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        var actor = ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        try {
            var request = mapper.readerFor(com.voltcanary.dashboard.templates.DashboardTemplatePreparation.Request.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(body);
            return preparation.prepare(actor, authorization, id, (com.voltcanary.dashboard.templates.DashboardTemplatePreparation.Request) request);
        } catch (java.io.IOException invalid) {
            throw new com.voltcanary.dashboard.domain.DashboardFailure("VALIDATION_ERROR", "Unsupported template intent fields or values");
        }
    }
}
