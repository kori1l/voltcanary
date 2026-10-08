package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.query.DashboardWidgetQueries;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.query.WidgetManifests;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardQueryController {
    private final DashboardOwnership ownership;
    private final DashboardWidgetQueries queries;
    private final WidgetManifests manifests;
    private final DefinitionValidation validation;
    private final ObjectMapper mapper;
    public DashboardQueryController(DashboardOwnership ownership, DashboardWidgetQueries queries,
            WidgetManifests manifests, DefinitionValidation validation, ObjectMapper mapper) {
        this.ownership = ownership; this.queries = queries; this.manifests = manifests;
        this.validation = validation; this.mapper = mapper;
    }
    @GetMapping("/dashboard-widget-types")
    public List<QueryTypes.Manifest> catalog(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ownership.resolveReadOnly(authorization);
        return manifests.all();
    }
    @PostMapping("/dashboards/{id}/query")
    public QueryTypes.Batch query(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestBody JsonNode body) {
        return execute(authorization, id, body, false);
    }
    @PostMapping("/dashboards/{id}/query-validation")
    public QueryTypes.Batch validate(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestBody JsonNode body) {
        return execute(authorization, id, body, true);
    }
    private QueryTypes.Batch execute(String authorization, UUID id, JsonNode body, boolean validateOnly) {
        var actor = ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        DashboardWidgetQueries.Request request;
        try { request = mapper.readerFor(DashboardWidgetQueries.Request.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body); }
        catch (Exception exception) { throw new DashboardFailure("VALIDATION_ERROR", "Unsupported query request fields"); }
        return queries.query(authorization, actor, id, request, validateOnly);
    }
}
