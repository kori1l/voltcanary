package com.voltcanary.telemetry.controller;

import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.telemetry.adapter.VariableSourceAccess;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.variable.VariableSeriesQueries;
import com.voltcanary.telemetry.variable.domain.VariableEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VariableTelemetryController {
    public record Preview(UUID deviceId, UUID dataSourceId, VariableContract contract, Map<String, Double> inputs) { }
    private final TelemetryAccessService access;
    private final VariableSourceAccess sources;
    private final VariableSeriesQueries queries;
    private final VariableEvaluator evaluator = new VariableEvaluator();

    public VariableTelemetryController(TelemetryAccessService access, VariableSourceAccess sources, VariableSeriesQueries queries) {
        this.access = access;
        this.sources = sources;
        this.queries = queries;
    }

    @PostMapping("/telemetry/variables/preview")
    public VariableEvaluator.Result preview(@RequestHeader HttpHeaders headers, @RequestBody Preview request) {
        authorize(headers, request.deviceId(), request.dataSourceId());
        return evaluator.evaluate(request.contract(), request.inputs());
    }

    @GetMapping("/telemetry/devices/{deviceId}/variables")
    public List<VariableSeriesQueries.Descriptor> descriptors(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID device) {
        authorizeDevice(headers, device);
        var descriptors = queries.descriptors(device);
        var active = sources.activeBatch(device, descriptors);
        return descriptors.stream().map(item -> new VariableSeriesQueries.Descriptor(
                item.variableId(), item.dataSourceId(), item.sourceId(), item.key(), item.label(), item.origin(), item.unit(),
                item.revision(), item.generation(), item.enabled() && active.contains(item.dataSourceId() + ":" + item.variableId()),
                item.metricToken())).toList();
    }

    @GetMapping(value = {"/telemetry/devices/{deviceId}/readings", "/telemetry/devices/{deviceId}/readings/export"},
            params = "variableId")
    public VariableSeriesQueries.Series readings(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID device, @RequestParam("variableId") UUID variable,
            @RequestParam("sourceId") UUID source, @RequestParam("from") Instant from, @RequestParam("to") Instant to,
            @RequestParam(value = "metric", required = false) String metric,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestParam(value = "cursor", required = false) Instant cursor,
            @RequestParam(value = "bucketSeconds", defaultValue = "0") long bucketSeconds,
            @RequestParam(value = "reducer", defaultValue = "AVG") String reducer) {
        if (metric != null) throw new IllegalArgumentException("Choose metric or variableId, not both");
        authorize(headers, device, source);
        return queries.read(device, source, variable, from, to, limit, cursor, bucketSeconds, reducer);
    }

    @GetMapping("/telemetry/devices/{deviceId}/variables/evaluations")
    public List<Map<String, Object>> outcomes(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID device, @RequestParam("sourceId") UUID source) {
        authorize(headers, device, source);
        return queries.outcomes(device, source);
    }

    @GetMapping(value = "/telemetry/devices/{deviceId}/latest", params = "variableId")
    public VariableSeriesQueries.Series latest(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID device, @RequestParam("variableId") UUID variable,
            @RequestParam("sourceId") UUID source) {
        authorize(headers, device, source);
        return queries.latest(device, source, variable);
    }

    private void authorize(HttpHeaders headers, UUID device, UUID source) {
        if (device == null || source == null) throw new IllegalArgumentException("Device and source are required");
        authorizeDevice(headers, device);
        sources.require(device, source);
    }

    private void authorizeDevice(HttpHeaders headers, UUID device) {
        access.requireReadAccess(device, headers.getFirst("X-User-Id"), headers.getFirst("X-User-Role"),
                headers.getFirst("X-Correlation-Id"), headers.getFirst("X-Internal-Service-Token"));
    }
}
