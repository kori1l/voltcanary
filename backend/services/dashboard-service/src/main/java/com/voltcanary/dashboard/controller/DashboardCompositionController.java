package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.templates.DashboardCompositionPreparation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardCompositionController {
    private final DashboardOwnership ownership;
    private final DefinitionValidation validation;
    private final DashboardCompositionPreparation preparation;
    private final ObjectMapper mapper;
    private final com.voltcanary.dashboard.query.WidgetManifests manifests;

    public DashboardCompositionController(DashboardOwnership ownership, DefinitionValidation validation,
            DashboardCompositionPreparation preparation, ObjectMapper mapper, com.voltcanary.dashboard.query.WidgetManifests manifests) {
        this.ownership = ownership;
        this.validation = validation;
        this.preparation = preparation;
        this.mapper = mapper;
        this.manifests = manifests;
    }

    @GetMapping("/dashboards/composition/widget-types")
    public java.util.List<java.util.Map<String, Object>> types(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ownership.resolveReadOnly(authorization);
        return manifests.all().stream().filter(item -> item.supported()).map(item -> java.util.Map.<String, Object>ofEntries(
                java.util.Map.entry("type", item.type()), java.util.Map.entry("typeVersion", item.typeVersion()),
                java.util.Map.entry("title", item.title()), java.util.Map.entry("description", item.description()),
                java.util.Map.entry("reducers", item.reducers()), java.util.Map.entry("units", item.units()),
                java.util.Map.entry("timeBasis", item.timeBasis()), java.util.Map.entry("maxSeries", item.maxSeries()),
                java.util.Map.entry("maxRows", item.maxRows()), java.util.Map.entry("maxDetailDevices", item.maxDetailDevices()),
                java.util.Map.entry("sizeConstraints", item.sizeConstraints()),
                java.util.Map.entry("visualizations", item.visualizations()), java.util.Map.entry("capabilities", item.capabilities()))).toList();
    }

    @PostMapping("/dashboards/composition/prepare")
    public DashboardCompositionPreparation.Prepared prepare(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode body, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var actor = ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        for (JsonNode widget : body.path("widgets")) {
            for (String flag : java.util.List.of("showTitle", "showMapDetails", "showMapNotices", "compact")) {
                if (widget.hasNonNull(flag) && !widget.path(flag).isBoolean()) {
                    throw new DashboardFailure("VALIDATION_ERROR", "Visibility must be a boolean", flag);
                }
            }
        }
        try {
            DashboardCompositionPreparation.Request request = mapper.readerFor(DashboardCompositionPreparation.Request.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
            return preparation.prepare(actor, authorization, request);
        } catch (java.io.IOException invalid) {
            throw new DashboardFailure("VALIDATION_ERROR", "Unsupported composition fields or values");
        }
    }
}
