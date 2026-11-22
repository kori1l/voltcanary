package com.voltcanary.dashboard.application;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDataChoices.Choice;
import com.voltcanary.dashboard.domain.DashboardDataChoices.Diagnostic;
import com.voltcanary.dashboard.domain.DashboardDataChoices.Resolution;
import com.voltcanary.dashboard.domain.DashboardDataChoices.View;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardCapabilityEvidence;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.query.QueryTypes.Manifest;
import com.voltcanary.dashboard.query.WidgetManifests;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Request-scoped capability resolution. The ordinary preparation remains the final authority. */
@Service
@RequiredArgsConstructor
public class DashboardCapabilityResolution {

    public record Request(List<UUID> deviceIds, String period, String purpose) {}

    private static final Set<String> PERIODS = Set.of(
        "LAST_1_HOUR",
        "LAST_6_HOURS",
        "LAST_24_HOURS",
        "LAST_7_DAYS",
        "LAST_30_DAYS"
    );
    private final DashboardOwnership ownership;
    private final DashboardCapabilityEvidence evidence;
    private final WidgetManifests manifests;

    public Resolution resolve(DashboardActor actor, String authorization, Request request) {
        if (
            request == null ||
            request.deviceIds() == null ||
            request.deviceIds().isEmpty() ||
            request.deviceIds().size() > 20 ||
            request.deviceIds().stream().anyMatch(java.util.Objects::isNull) ||
            Set.copyOf(request.deviceIds()).size() != request.deviceIds().size() ||
            (request.period() != null && !PERIODS.contains(request.period())) ||
            (request.purpose() != null &&
                !Set.of("CURRENT", "HISTORY", "MAP", "DOMAIN").contains(request.purpose()))
        ) {
            throw new DashboardFailure(
                "VALIDATION_ERROR",
                "Choose 1–20 devices, a supported period and purpose"
            );
        }
        for (UUID id : request.deviceIds())
            if (!ownership.canReadDevice(authorization, id, actor)) {
                throw new DashboardFailure("NOT_FOUND", "Selected device is unavailable");
            }
        var choices = new ArrayList<Choice>();
        var diagnostics = new ArrayList<Diagnostic>();
        boolean sourcePeriod =
            request.period() == null ||
            !Set.of("LAST_7_DAYS", "LAST_30_DAYS").contains(request.period());
        for (UUID id : request.deviceIds()) {
            var device = evidence.read(authorization, id);
            for (var metric : device.metrics()) {
                if (metric.name().startsWith("var_")) continue;
                choices.add(
                    choice(
                        id,
                        null,
                        null,
                        "REGISTERED_NUMERIC_METRIC",
                        metric.name(),
                        metric.name(),
                        "number",
                        metric.unit(),
                        "UNKNOWN",
                        "UNKNOWN",
                        null,
                        views(
                            "REGISTERED_NUMERIC_METRIC",
                            "number",
                            metric.unit(),
                            sourcePeriod,
                            request.purpose()
                        )
                    )
                );
            }
            if (device.type().equals("VEHICLE")) for (var source : device.sources()) {
                choices.add(
                    choice(
                        id,
                        source.id(),
                        source.externalId(),
                        "RECORDED_VEHICLE_FIELD",
                        null,
                        "Recorded position",
                        "coordinates",
                        "",
                        source.freshness().equals("NO_DATA") ? "EMPTY" : "UNKNOWN",
                        source.freshness(),
                        null,
                        views(
                            "RECORDED_VEHICLE_FIELD",
                            "coordinates",
                            "",
                            sourcePeriod,
                            request.purpose()
                        )
                    )
                );
                for (var field : device.fields())
                    if (field.sourceId().equals(source.externalId())) {
                        choices.add(
                            choice(
                                id,
                                source.id(),
                                source.externalId(),
                                "RECORDED_VEHICLE_FIELD",
                                field.key(),
                                field.label(),
                                field.valueType(),
                                field.unit(),
                                field.observed() ? "OBSERVED" : "MISSING",
                                source.freshness(),
                                field.recordedAt(),
                                views(
                                    "RECORDED_VEHICLE_FIELD",
                                    field.valueType(),
                                    field.unit(),
                                    sourcePeriod,
                                    request.purpose()
                                )
                            )
                        );
                    }
            }
            choices.add(
                choice(
                    id,
                    null,
                    null,
                    "DOMAIN_EVIDENCE",
                    null,
                    "Domain evidence",
                    "domain-facts",
                    "",
                    "UNKNOWN",
                    "UNKNOWN",
                    null,
                    views("DOMAIN_EVIDENCE", "domain-facts", "", sourcePeriod, request.purpose())
                )
            );
        }
        if (request.purpose() == null) for (var choice : choices)
            if (choice.views().isEmpty()) {
                diagnostics.add(
                    new Diagnostic(
                        "UNSUPPORTED_DATA_OR_PERIOD",
                        choice.deviceId(),
                        choice.key(),
                        List.of(choice.choiceId()),
                        "No supported widget for this declared type/unit and period"
                    )
                );
            }
        choices.removeIf(choice -> choice.views().isEmpty());
        if (choices.size() > 200) throw new DashboardFailure(
            "LIMIT_EXCEEDED",
            "Narrow the selection or requested purpose; more than 200 data choices"
        );
        for (var choice : choices)
            if (choice.sourceId() != null) {
                var matches = choices
                    .stream()
                    .filter(
                        other ->
                            other.deviceId().equals(choice.deviceId()) &&
                            other.family().equals(choice.family()) &&
                            (java.util.Objects.equals(choice.key(), other.key()) ||
                                choice.label().equals(other.label()))
                    )
                    .map(Choice::choiceId)
                    .distinct()
                    .sorted()
                    .toList();
                if (
                    matches.size() > 1 &&
                    diagnostics
                        .stream()
                        .noneMatch(
                            d ->
                                choice.deviceId().equals(d.deviceId()) &&
                                java.util.Objects.equals(choice.key(), d.key())
                        )
                ) {
                    diagnostics.add(
                        new Diagnostic(
                            "AMBIGUOUS_SOURCE",
                            choice.deviceId(),
                            choice.key(),
                            matches,
                            "Choose the recorded source explicitly"
                        )
                    );
                }
            }
        if (choices.isEmpty()) diagnostics.add(
            new Diagnostic(
                "NO_KNOWN_CAPABILITY",
                null,
                null,
                List.of(),
                "No compatible metadata; absence is not a zero reading"
            )
        );
        String snapshot = identity(choices.toString());
        return new Resolution(1, snapshot, List.copyOf(choices), List.copyOf(diagnostics));
    }

    private List<View> views(
        String family,
        String valueType,
        String unit,
        boolean sourcePeriod,
        String purpose
    ) {
        var result = new ArrayList<View>();
        for (var manifest : manifests.all()) {
            var descriptor = manifest.capabilities();
            if (
                !manifest.supported() ||
                !family.equals(descriptor.dataKind()) ||
                !descriptor.valueTypes().contains(valueType)
            ) continue;
            if (
                family.equals("REGISTERED_NUMERIC_METRIC") && !manifest.units().contains(unit)
            ) continue;
            if (family.equals("RECORDED_VEHICLE_FIELD") && !sourcePeriod) continue;
            String role = role(manifest);
            if (purpose != null && !purpose.equals(role)) continue;
            var visualizations = descriptor
                .visualizations()
                .stream()
                .filter(view ->
                    descriptor
                        .visualizationValueTypes()
                        .getOrDefault(view, List.of())
                        .contains(valueType)
                )
                .toList();
            boolean needsConfiguration = family.equals("DOMAIN_EVIDENCE");
            result.add(
                new View(
                    manifest.type(),
                    manifest.typeVersion(),
                    role,
                    visualizations,
                    manifest.reducers().stream().map(Enum::name).toList(),
                    needsConfiguration ? "REQUIRES_CONFIGURATION" : "COMPATIBLE",
                    needsConfiguration
                        ? List.of(
                              "Ordinary widget configuration and semantic validation; data presence alone is insufficient"
                          )
                        : List.of()
                )
            );
        }
        return List.copyOf(result);
    }

    private String role(Manifest manifest) {
        if (manifest.capabilities().dataKind().equals("DOMAIN_EVIDENCE")) return "DOMAIN";
        if (manifest.capabilities().valueTypes().contains("coordinates")) return "MAP";
        return manifest.timeBasis().equals("SNAPSHOT") ? "CURRENT" : "HISTORY";
    }

    private Choice choice(
        UUID device,
        UUID source,
        String external,
        String family,
        String key,
        String label,
        String type,
        String unit,
        String current,
        String freshness,
        java.time.Instant recordedAt,
        List<View> views
    ) {
        return new Choice(
            identity(
                List.of(
                    device.toString(),
                    String.valueOf(source),
                    family,
                    String.valueOf(key),
                    type,
                    unit
                ).toString()
            ),
            device,
            source,
            external,
            family,
            key,
            label,
            type,
            unit,
            current,
            freshness,
            "UNKNOWN",
            recordedAt,
            views
        );
    }

    private String identity(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void requireCompatible(
        Resolution resolution,
        String type,
        List<UUID> devices,
        UUID source,
        String metric,
        String unit,
        String field,
        String visualization
    ) {
        requireCompatible(
            resolution,
            type,
            devices,
            source,
            metric,
            unit,
            field,
            visualization,
            null
        );
    }

    public void requireCompatible(
        Resolution resolution,
        String type,
        List<UUID> devices,
        UUID source,
        String metric,
        String unit,
        String field,
        String visualization,
        String expectedChoiceId
    ) {
        var manifest = manifests
            .find(type, 1)
            .orElseThrow(() -> new DashboardFailure("VALIDATION_ERROR", "Unsupported widget"));
        String family = manifest.capabilities().dataKind();
        if (family.equals("DOMAIN_EVIDENCE") || family.equals("OPERATOR_TEXT")) return;
        for (UUID device : devices) {
            var matches = resolution
                .choices()
                .stream()
                .filter(
                    choice ->
                        choice.deviceId().equals(device) &&
                        choice.family().equals(family) &&
                        (expectedChoiceId == null || expectedChoiceId.equals(choice.choiceId())) &&
                        (source == null || source.equals(choice.sourceId())) &&
                        (family.equals("REGISTERED_NUMERIC_METRIC")
                            ? java.util.Objects.equals(metric, choice.key()) &&
                              java.util.Objects.equals(unit, choice.unit())
                            : java.util.Objects.equals(field, choice.key()))
                )
                .filter(choice ->
                    choice
                        .views()
                        .stream()
                        .anyMatch(
                            view ->
                                view.widgetType().equals(type) &&
                                (visualization == null ||
                                    view.visualizations().contains(visualization))
                        )
                )
                .toList();
            if (matches.size() != 1) {
                throw new DashboardFailure(
                    "VALIDATION_ERROR",
                    matches.size() > 1
                        ? "Choose a source explicitly; multiple exact recorded fields match"
                        : "Widget data or view is incompatible with current authorized metadata",
                    "widgets.data"
                );
            }
        }
    }
}
