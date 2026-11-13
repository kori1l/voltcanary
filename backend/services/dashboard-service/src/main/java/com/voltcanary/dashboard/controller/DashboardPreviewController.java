package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.query.DashboardWidgetQueries;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.templates.TemplateDefinitionChecks;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Read-only preview: no dashboard, revision, creation receipt or publication is written. */
@RestController
public class DashboardPreviewController {
    public record Request(DashboardDefinition definition) { }
    private final DashboardOwnership ownership;
    private final DefinitionValidation validation;
    private final TemplateDefinitionChecks checks;
    private final DashboardWidgetQueries queries;
    private final ObjectMapper mapper;

    public DashboardPreviewController(DashboardOwnership ownership, DefinitionValidation validation,
            TemplateDefinitionChecks checks, DashboardWidgetQueries queries, ObjectMapper mapper) {
        this.ownership = ownership;
        this.validation = validation;
        this.checks = checks;
        this.queries = queries;
        this.mapper = mapper;
    }

    @PostMapping("/dashboards/preview/query")
    public QueryTypes.Batch preview(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode body, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var actor = ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        Request request;
        try {
            request = mapper.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
        } catch (java.io.IOException invalid) {
            throw new DashboardFailure("VALIDATION_ERROR", "Unsupported preview fields");
        }
        checks.validate(actor, authorization, request.definition());
        return queries.preview(authorization, actor, request.definition());
    }
}
