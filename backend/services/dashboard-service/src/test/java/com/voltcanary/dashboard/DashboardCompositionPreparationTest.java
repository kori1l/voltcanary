package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionBindings;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import com.voltcanary.dashboard.query.WidgetManifests;
import com.voltcanary.dashboard.query.WidgetQueryDecoder;
import com.voltcanary.dashboard.templates.DashboardCompositionPreparation;
import com.voltcanary.dashboard.templates.DashboardTemplateAssembler;
import com.voltcanary.dashboard.templates.TemplateDefinitionChecks;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardCompositionPreparationTest {
    @Test void titleAndSettingsPreserveAllBreakpointGeometryWhilePartialPlacementOnlyReflowsRows() {
        vehicle(1);
        var map = panel("map", "vehicle-map", null, "FULL", null);
        var graph = panel("history", "time-series", "speedKmh", "FULL", null);
        var card = panel("current", "latest-value", "speedKmh", "THIRD", null);
        var baseline = service().prepare(actor, "Bearer owner", request(List.of(map, graph, card))).definition();
        var renamed = new DashboardCompositionPreparation.Panel("current", "latest-value", "Current speed", null,
                "speedKmh", "km/h", null, null, null, null, "THIRD", 4, null, null, null, null, null, null,
                java.util.Map.of("showTitle", false));
        var settingsOnly = service().prepare(actor, "Bearer owner", request(List.of(map, graph, renamed))).definition();
        assertThat(settingsOnly.layouts()).isEqualTo(baseline.layouts());
        assertThat(settingsOnly.widgets().get(0)).isEqualTo(baseline.widgets().get(0));
        assertThat(settingsOnly.widgets().get(1)).isEqualTo(baseline.widgets().get(1));
        assertThat(settingsOnly.widgets().get(2).query()).isEqualTo(baseline.widgets().get(2).query());
        var moved = service().prepare(actor, "Bearer owner", request(List.of(card, map, graph))).definition();
        assertThat(moved.widgets()).containsExactly(baseline.widgets().get(2), baseline.widgets().get(0), baseline.widgets().get(1));
        for (String breakpoint : List.of("desktop", "tablet", "mobile")) {
            var placements = moved.layouts().path(breakpoint);
            assertThat(placements.get(0).path("widgetId").asText()).isEqualTo("current");
            assertThat(placements.get(1).path("widgetId").asText()).isEqualTo("map");
            assertThat(placements.get(2).path("widgetId").asText()).isEqualTo("history");
            for (int index = 1; index < 3; index++) {
                assertThat(placements.get(index).path("y").asInt()).isGreaterThanOrEqualTo(
                        placements.get(index - 1).path("y").asInt() + placements.get(index - 1).path("h").asInt());
            }
        }
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final DashboardOwnership ownership = mock(DashboardOwnership.class);
    private final TemplateDeviceCatalogue catalogue = mock(TemplateDeviceCatalogue.class);
    private final DashboardActor actor = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
    private final UUID device = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final DashboardTemplateAssembler digests = new DashboardTemplateAssembler(mapper);

    private DashboardCompositionPreparation service() {
        var manifests = new WidgetManifests();
        var reads = mock(DashboardEvidenceReads.class);
        var checks = new TemplateDefinitionChecks(new DefinitionValidation(mapper), new DefinitionBindings(ownership, mapper, reads), manifests, new WidgetQueryDecoder(mapper));
        return new DashboardCompositionPreparation(ownership, catalogue, manifests, checks, digests, mapper,
                mock(com.voltcanary.dashboard.application.DashboardCapabilityResolution.class));
    }
    private void vehicle(int sources) {
        when(ownership.canReadDevice(eq("Bearer owner"), eq(device), eq(actor))).thenReturn(true);
        var registered = java.util.stream.IntStream.range(0, sources).mapToObj(index -> new TemplateDeviceCatalogue.Source(index == 0 ? source : UUID.randomUUID(), "hsl-bus-" + index)).toList();
        when(catalogue.read("Bearer owner", device)).thenReturn(new TemplateDeviceCatalogue.Device(device, "Helsinki bus", "VEHICLE",
                List.of(new TemplateDeviceCatalogue.Metric("speedKmh", "km/h")), registered));
    }
    private DashboardCompositionPreparation.Panel panel(String key, String type, String metric, String width, String period) {
        return new DashboardCompositionPreparation.Panel(key, type, key, null, metric, metric == null ? null : "km/h", null, null, null, null, width, 4, period);
    }
    private DashboardCompositionPreparation.Request request(List<DashboardCompositionPreparation.Panel> widgets) {
        return new DashboardCompositionPreparation.Request("Helsinki bus", "LAST_24_HOURS", List.of(device), widgets);
    }

    @Test void combinesGenuineMapAndGraphsWithoutAnyTemplateAndPacksTheExactDefinition() {
        vehicle(1);
        var request = request(List.of(panel("map", "vehicle-map", null, "FULL", null), panel("speed", "time-series", "speedKmh", "HALF", null), panel("latest", "latest-value", "speedKmh", "HALF", null)));
        var prepared = service().prepare(actor, "Bearer owner", request);
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets()).extracting(widget -> widget.type()).containsExactly("vehicle-map", "time-series", "latest-value");
        assertThat(prepared.definition().widgets().getFirst().query().path("sourceId").asText()).isEqualTo("hsl-bus-0");
        assertThat(prepared.definition().widgets().get(1).query().path("series").get(0).path("metric").asText()).isEqualTo("speedKmh");
        assertThat(prepared.definition().layouts().path("desktop").get(2).path("x").asInt()).isEqualTo(6);
        assertThat(prepared.definition().layouts().path("mobile").get(2).path("y").asInt()).isEqualTo(8);
        assertThat(prepared.digest()).isEqualTo(digests.digest(prepared.definition()));
        assertThat(service().prepare(actor, "Bearer owner", request).digest()).isEqualTo(prepared.digest());
    }
    @Test void rejectsForeignSelectionBeforeReadingLabels() {
        assertThatThrownBy(() -> service().prepare(actor, "Bearer owner", request(List.of(panel("map", "vehicle-map", null, "FULL", null)))))
                .isInstanceOf(DashboardFailure.class);
        verifyNoInteractions(catalogue);
    }
    @Test void asksAboutAmbiguousSourcesAndUnknownMetricsWithoutDroppingRequestedWidgets() {
        vehicle(2);
        assertThat(service().prepare(actor, "Bearer owner", request(List.of(panel("map", "vehicle-map", null, "FULL", null)))).status()).isEqualTo("NEEDS_INPUT");
        var absent = service().prepare(actor, "Bearer owner", request(List.of(panel("absent", "time-series", "madeUp", "FULL", null))));
        assertThat(absent.status()).isEqualTo("NEEDS_INPUT");
        assertThat(absent.definition()).isNull();
    }
    @Test void doesNotReplaceUnknownTypesOrDuplicateKeysWithAConvenientTemplate() {
        vehicle(1);
        assertThatThrownBy(() -> service().prepare(actor, "Bearer owner", request(List.of(panel("map", "invented", null, "FULL", null))))).isInstanceOf(DashboardFailure.class);
        var map = panel("map", "vehicle-map", null, "FULL", null);
        assertThatThrownBy(() -> service().prepare(actor, "Bearer owner", request(List.of(map, map)))).isInstanceOf(DashboardFailure.class);
    }
    @Test void mapAndChartMayUseDifferentExplicitPeriodsWithoutTruncatingTheChart() {
        vehicle(1);
        var request = new DashboardCompositionPreparation.Request("Helsinki bus", "LAST_7_DAYS", List.of(device), List.of(panel("map", "vehicle-map", null, "FULL", "LAST_1_HOUR"), panel("speed", "time-series", "speedKmh", "FULL", null)));
        var prepared = service().prepare(actor, "Bearer owner", request);
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets().getFirst().query().path("time").path("preset").asText()).isEqualTo("LAST_1_HOUR");
        assertThat(prepared.definition().widgets().get(1).query().path("time").path("preset").asText()).isEqualTo("LAST_7_DAYS");
    }

    @Test void preparesVehicleFieldsAndPreservesMinimalMapPresentation() {
        vehicle(1);
        var map = new DashboardCompositionPreparation.Panel("map", "vehicle-map", "Map", null, null, null, null, null, null, null, "FULL", 6, null, false, false, null);
        var doors = new DashboardCompositionPreparation.Panel("doors", "vehicle-timeline", "Doors", null, null, null, null, null, null, null, "HALF", 4, null, true, null, "transport.doorsOpen");
        var stop = new DashboardCompositionPreparation.Panel("stop", "vehicle-value", "Stop", null, null, null, null, null, null, null, "HALF", 3, null, false, null, "transport.nextStopId");
        var prepared = service().prepare(actor, "Bearer owner", request(List.of(map, doors, stop)));
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets().getFirst().presentation().path("showTitle").asBoolean()).isFalse();
        assertThat(prepared.definition().widgets().getFirst().presentation().path("showMapDetails").asBoolean()).isFalse();
        assertThat(prepared.definition().widgets().get(1).presentation().path("vehicleField").asText()).isEqualTo("transport.doorsOpen");
        assertThat(prepared.definition().widgets().get(2).query().path("sourceId").asText()).isEqualTo("hsl-bus-0");
        assertThat(prepared.digest()).isEqualTo(digests.digest(prepared.definition()));
    }
    @Test void fieldWidgetRequiresAnExplicitFieldInsteadOfAnInventedNumericMetric() {
        vehicle(1);
        assertThat(service().prepare(actor, "Bearer owner", request(List.of(panel("doors", "vehicle-value", null, "HALF", null)))).status()).isEqualTo("NEEDS_INPUT");
    }
    @Test void preparesOneRowCompactValueAndPreservesItInDigest() {
        vehicle(1);
        var card = new DashboardCompositionPreparation.Panel("doors", "vehicle-value", "Двери", null,
                null, null, null, null, null, null, "THIRD", 1, null, true, null, "transport.doorsOpen", true);
        var prepared = service().prepare(actor, "Bearer owner", request(List.of(card)));
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets().getFirst().presentation().path("compact").asBoolean()).isTrue();
        for (String breakpoint : List.of("desktop", "tablet", "mobile")) {
            assertThat(prepared.definition().layouts().path(breakpoint).get(0).path("h").asInt()).isEqualTo(1);
        }
        assertThat(prepared.digest()).isEqualTo(digests.digest(prepared.definition()));
        var invalid = new DashboardCompositionPreparation.Panel("doors", "vehicle-value", "Двери", null,
                null, null, null, null, null, null, "THIRD", 0, null, true, null, "transport.doorsOpen", true);
        assertThatThrownBy(() -> service().prepare(actor, "Bearer owner", request(List.of(invalid)))).isInstanceOf(DashboardFailure.class);
    }
    @Test void retainsExplicitHiddenMapNoticesInThePreparedDefinition() {
        vehicle(1);
        var map = new DashboardCompositionPreparation.Panel("map", "vehicle-map", "Карта", null,
                null, null, null, null, null, null, "FULL", 6, null, false, false, null, null, false);
        var prepared = service().prepare(actor, "Bearer owner", request(List.of(map)));
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets().getFirst().presentation().path("showMapNotices").isBoolean()).isTrue();
        assertThat(prepared.definition().widgets().getFirst().presentation().path("showMapNotices").asBoolean()).isFalse();
        assertThat(prepared.digest()).isEqualTo(digests.digest(prepared.definition()));
    }
    @Test void typedSettingsUseTheSameCatalogueAndPreserveThePreparedBinding() {
        vehicle(1);
        var card = new DashboardCompositionPreparation.Panel("doors", "vehicle-value", "Doors", null,
                null, null, null, null, null, null, "THIRD", 1, null, null, null, "transport.doorsOpen", null, null,
                java.util.Map.of("showTitle", false, "trueLabel", "Open", "compact", true));
        var prepared = service().prepare(actor, "Bearer owner", request(List.of(card)));
        var result = prepared.definition().widgets().getFirst();
        assertThat(result.presentation().path("showTitle").asBoolean()).isFalse();
        assertThat(result.presentation().path("trueLabel").asText()).isEqualTo("Open");
        assertThat(result.presentation().path("compact").asBoolean()).isTrue();
        assertThat(result.query().path("sourceId").asText()).isEqualTo("hsl-bus-0");
        var reset = new java.util.LinkedHashMap<String, Object>();
        reset.put("showTitle", null);
        var resetCard = new DashboardCompositionPreparation.Panel("doors", "vehicle-value", "Doors", null,
                null, null, null, null, null, null, "THIRD", 1, null, null, null, "transport.doorsOpen", null, null, reset);
        assertThat(service().prepare(actor, "Bearer owner", request(List.of(resetCard))).definition().widgets().getFirst()
                .presentation().path("showTitle").asBoolean()).isFalse();
    }

    @Test void preparesADoorFieldGraphWithoutInventingANumericTelemetryMetric() {
        vehicle(1);
        var graph = new DashboardCompositionPreparation.Panel("door-history", "vehicle-timeline", "Двери", null,
                null, null, null, "line", null, null, "FULL", 4, null, true, null, "transport.doorsOpen");
        var prepared = service().prepare(actor, "Bearer owner", request(List.of(graph)));
        assertThat(prepared.status()).isEqualTo("READY");
        var widget = prepared.definition().widgets().getFirst();
        assertThat(widget.presentation().path("visualization").asText()).isEqualTo("line");
        assertThat(widget.presentation().path("vehicleField").asText()).isEqualTo("transport.doorsOpen");
        assertThat(widget.query().has("metric")).isFalse();
        assertThat(widget.query().path("sourceId").asText()).isEqualTo("hsl-bus-0");
    }
}
