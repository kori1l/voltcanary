package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Bounded translation of dashboard-owned choices into task-local references. */
final class DashboardCapabilityContext {

    private final RestClient dashboards;

    DashboardCapabilityContext(RestClient dashboards) {
        this.dashboards = dashboards;
    }

    void enrich(
        Map<String, Object> context,
        List<ResourceRef> refs,
        List<UUID> devices,
        Map<String, String> deviceRefs,
        Map<String, Object> input,
        String authorization
    ) {
        Map<String, Object> result;
        try {
            result = dashboards
                .post()
                .uri("/dashboards/composition/capabilities")
                .header("Authorization", authorization)
                .body(Map.of("deviceIds", devices, "period", "LAST_1_HOUR"))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 403) {
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Selected dashboard data is unavailable"
                );
            }
            if (failure.getStatusCode().value() == 422) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Narrow the dashboard data selection"
            );
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dashboard data capabilities unavailable"
            );
        } catch (RestClientException failure) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dashboard data capabilities unavailable"
            );
        }
        if (
            result == null ||
            !(result.get("snapshotId") instanceof String snapshot) ||
            !(result.get("choices") instanceof List<?> choices) ||
            choices.size() > 60
        ) throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Narrow the dashboard data selection; capability catalogue unavailable or exceeds 60 choices"
        );
        var selectedSources = sourceSelections(input, choices, deviceRefs, refs);
        var ambiguous = new java.util.HashSet<String>();
        if (
            result.get("diagnostics") instanceof List<?> diagnostics
        ) for (Object item : diagnostics) {
            if (
                item instanceof Map<?, ?> diagnostic &&
                "AMBIGUOUS_SOURCE".equals(diagnostic.get("code")) &&
                diagnostic.get("choiceIds") instanceof List<?> ids
            ) {
                ids.forEach(id -> ambiguous.add(String.valueOf(id)));
            }
        }
        boolean unresolved = choices
            .stream()
            .anyMatch(
                item ->
                    item instanceof Map<?, ?> choice &&
                    ambiguous.contains(String.valueOf(choice.get("choiceId"))) &&
                    !selectedSources.containsKey(String.valueOf(choice.get("deviceId")))
            );
        if (!unresolved && !selectedSources.isEmpty()) for (var ref : refs)
            if (ref.kind().equals("source") && selectedSources.containsValue(ref.resourceId())) {
                context.put("selectedDashboardSourceRef", ref.ref());
                break;
            }
        var summaries = new ArrayList<Map<String, Object>>();
        var sourceLabels = new LinkedHashMap<String, String>();
        for (Object item : choices)
            if (
                item instanceof Map<?, ?> choice &&
                choice.get("sourceId") instanceof String sourceId
            ) {
                sourceLabels.computeIfAbsent(
                    sourceId,
                    ignored -> "Источник " + (sourceLabels.size() + 1)
                );
            }
        String namespace = Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
        for (Object item : choices) {
            if (
                !(item instanceof Map<?, ?> choice) ||
                !(choice.get("choiceId") instanceof String id) ||
                !(choice.get("deviceId") instanceof String device) ||
                !deviceRefs.containsKey(device)
            ) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid dashboard data capability response"
                );
            }
            String selectedSource = selectedSources.get(device);
            if (
                selectedSource != null &&
                choice.get("sourceId") != null &&
                !selectedSource.equals(choice.get("sourceId"))
            ) continue;
            if (
                selectedSource != null && "REGISTERED_NUMERIC_METRIC".equals(choice.get("family"))
            ) continue;
            var stable = new LinkedHashMap<String, Object>();
            choice.forEach((key, value) -> {
                if (key instanceof String text && value != null) stable.put(text, value);
            });
            stable.put("snapshotId", snapshot);
            stable.put("deviceRef", deviceRefs.get(device));
            if (choice.get("sourceId") instanceof String sourceId) {
                stable.put(
                    "sourceChoiceLabel",
                    sourceLabels.computeIfAbsent(
                        sourceId,
                        ignored -> "Источник " + (sourceLabels.size() + 1)
                    )
                );
            }
            boolean requiresSourceChoice = selectedSource == null && ambiguous.contains(id);
            stable.put("requiresSourceChoice", requiresSourceChoice);
            String ref = "d" + (summaries.size() + 1) + "_" + fieldHint(choice) + "_" + namespace;
            refs.add(new ResourceRef(ref, "dashboard-data", id, Map.copyOf(stable)));
            var summary = new LinkedHashMap<String, Object>();
            summary.put("dataRef", ref);
            summary.put("deviceRef", deviceRefs.get(device));
            summary.put("requiresSourceChoice", requiresSourceChoice);
            if (choice.get("externalSourceId") != null) summary.put(
                "sourceLabel",
                choice.get("externalSourceId")
            );
            if (stable.get("sourceChoiceLabel") != null) summary.put(
                "sourceChoiceLabel",
                stable.get("sourceChoiceLabel")
            );
            for (String key : List.of(
                "family",
                "key",
                "label",
                "valueType",
                "unit",
                "currentStatus",
                "sourceFreshness",
                "historyStatus"
            )) {
                if (choice.get(key) != null) summary.put(key, choice.get(key));
            }
            if (choice.get("views") instanceof List<?> views) summary.put(
                "views",
                views
                    .stream()
                    .map(itemView -> {
                        var compact = new LinkedHashMap<String, Object>();
                        if (itemView instanceof Map<?, ?> view) for (String key : List.of(
                            "widgetType",
                            "purpose",
                            "visualizations",
                            "reducers",
                            "readiness"
                        )) {
                            if (view.get(key) != null) compact.put(key, view.get(key));
                        }
                        return Map.copyOf(compact);
                    })
                    .toList()
            );
            if (choice.get("sourceId") instanceof String source) refs.stream()
                .filter(r -> r.kind().equals("source") && r.resourceId().equals(source))
                .findFirst()
                .ifPresent(r -> summary.put("sourceRef", r.ref()));
            summaries.add(Map.copyOf(summary));
        }
        context.put("dashboardDataChoices", List.copyOf(summaries));
        if (result.get("diagnostics") instanceof List<?> diagnostics) {
            context.put(
                "dashboardDataDiagnostics",
                diagnostics
                    .stream()
                    .map(item ->
                        item instanceof Map<?, ?> diagnostic
                            ? Map.of(
                                  "code",
                                  String.valueOf(diagnostic.get("code")),
                                  "message",
                                  String.valueOf(diagnostic.get("message"))
                              )
                            : Map.of("code", "UNKNOWN")
                    )
                    .toList()
            );
        }
    }

    private String fieldHint(Map<?, ?> choice) {
        Object key = choice.get("key");
        String hint =
            key instanceof String text
                ? text.replaceFirst("^transport\\.", "")
                : "coordinates".equals(choice.get("valueType"))
                  ? "position"
                  : "data";
        hint = hint.replaceAll("[^A-Za-z0-9_]", "_");
        String kind = "REGISTERED_NUMERIC_METRIC".equals(choice.get("family")) ? "metric" : "field";
        return (
            (hint.isEmpty() ? "data" : hint.substring(0, Math.min(12, hint.length()))) + "_" + kind
        );
    }

    private Map<String, String> sourceSelections(
        Map<String, Object> input,
        List<?> choices,
        Map<String, String> devices,
        List<ResourceRef> refs
    ) {
        var preferences = new LinkedHashMap<String, String>();
        if (input.get("sourceSelections") instanceof Map<?, ?> sources) sources.forEach(
            (device, source) -> {
                if (
                    !(device instanceof String id) ||
                    !devices.containsKey(id) ||
                    !(source instanceof String value)
                ) {
                    throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Source selections must belong to selected devices"
                    );
                }
                preferences.put(id, value);
            }
        );
        if (preferences.isEmpty() && input.get("sourceId") instanceof String source) {
            if (devices.size() != 1) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Specify sourceSelections separately for each selected device"
            );
            preferences.put(devices.keySet().iterator().next(), source);
        }
        var resolved = new LinkedHashMap<String, String>();
        preferences.forEach((device, source) -> {
            // An enabled source can be owned by this device before it exposes any fields.
            if (
                refs
                    .stream()
                    .anyMatch(
                        ref ->
                            ref.kind().equals("source") &&
                            source.equals(ref.resourceId()) &&
                            device.equals(ref.summary().get("deviceId"))
                    )
            ) {
                resolved.put(device, source);
                return;
            }
            var matches = choices
                .stream()
                .filter(
                    item ->
                        item instanceof Map<?, ?> choice &&
                        device.equals(choice.get("deviceId")) &&
                        choice.get("sourceId") != null &&
                        (source.equals(choice.get("sourceId")) ||
                            source.equals(choice.get("externalSourceId")))
                )
                .map(item -> String.valueOf(((Map<?, ?>) item).get("sourceId")))
                .distinct()
                .toList();
            if (matches.size() != 1) throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Selected dashboard source is unavailable or ambiguous"
            );
            resolved.put(device, matches.getFirst());
        });
        return Map.copyOf(resolved);
    }
}
