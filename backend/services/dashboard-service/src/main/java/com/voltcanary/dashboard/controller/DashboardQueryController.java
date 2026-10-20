package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.query.DashboardWidgetQueries;
import com.voltcanary.dashboard.query.CompactDashboardEvidence;
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
    private final CompactDashboardEvidence compact;
    @org.springframework.web.bind.annotation.ModelAttribute
    public void privateEvidenceHeaders(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
    }
    public DashboardQueryController(DashboardOwnership ownership, DashboardWidgetQueries queries,
            WidgetManifests manifests, DefinitionValidation validation, ObjectMapper mapper, CompactDashboardEvidence compact) {
        this.ownership = ownership; this.queries = queries; this.manifests = manifests;
        this.validation = validation; this.mapper = mapper;
        this.compact = compact;
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
    @PostMapping(value="/dashboards/{id}/query-progressive",produces="application/x-ndjson")
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody progressive(
            @RequestHeader(value="Authorization",required=false) String authorization, @PathVariable("id") UUID id, @RequestBody JsonNode body,
            jakarta.servlet.http.HttpServletResponse response) {
        var actor=ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        var request=decode(body);
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        response.setContentType("application/x-ndjson");
        return output -> {
            var sentScopes=new java.util.HashSet<String>();
            var delivered=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                var batch=queries.query(authorization,actor,id,request,false,group -> {
                    delivered.set(true);
                    var scopes=new java.util.LinkedHashMap<String,JsonNode>();
                    group.scopes().forEach((key,value)->{if(sentScopes.add(key))scopes.put(key,value);});
                    var wire=new QueryTypes.Batch(group.dashboardId(),group.revision(),group.draftEtag(),group.contextHash(),group.asOf(),group.returnedAt(),group.cached(),group.panels(),scopes);
                    try { output.write(mapper.writeValueAsBytes(wire)); output.write('\n'); output.flush(); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
                if (!delivered.get()) { output.write(mapper.writeValueAsBytes(batch)); output.write('\n'); }
                output.write(mapper.writeValueAsBytes(mapper.createObjectNode().put("complete",true).put("contextHash",batch.contextHash())));
                output.write('\n'); output.flush();
            } catch (DashboardFailure failure) {
                output.write(mapper.writeValueAsBytes(mapper.createObjectNode().put("errorCode",failure.code()).put("explanation",failure.getMessage())));
                output.write('\n'); output.flush();
            }
        };
    }
    private QueryTypes.Batch execute(String authorization, UUID id, JsonNode body, boolean validateOnly) {
        var actor = ownership.resolveReadOnly(authorization);
        validation.validatePayload(body);
        var request=decode(body);
        var batch = queries.query(authorization, actor, id, request, validateOnly);
        return batch;
    }
    private DashboardWidgetQueries.Request decode(JsonNode body) {
        try { return mapper.readerFor(DashboardWidgetQueries.Request.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body); }
        catch (Exception exception) { throw new DashboardFailure("VALIDATION_ERROR", "Unsupported query request fields"); }
    }
    @GetMapping("/dashboards/{id}/query-snapshots/{hash}/panels/{widgetId}")
    public QueryTypes.Panel details(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @PathVariable("hash") String hash, @PathVariable("widgetId") String widgetId) {
        return compact.details(authorization, ownership.resolveReadOnly(authorization), id, hash, widgetId, queries);
    }
}
