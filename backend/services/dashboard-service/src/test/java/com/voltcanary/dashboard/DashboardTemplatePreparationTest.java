package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.*;
import com.voltcanary.dashboard.domain.*;
import com.voltcanary.dashboard.port.*;
import com.voltcanary.dashboard.query.*;
import com.voltcanary.dashboard.templates.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardTemplatePreparationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DashboardOwnership ownership = mock(DashboardOwnership.class);
    private final TemplateDeviceCatalogue catalogue = mock(TemplateDeviceCatalogue.class);
    private final DashboardActor actor = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
    private final WidgetManifests manifests = new WidgetManifests();
    private final DashboardTemplateAssembler assembler = new DashboardTemplateAssembler(mapper);

    private DashboardTemplatePreparation service() {
        var checks = new TemplateDefinitionChecks(new DefinitionValidation(mapper),
                new DefinitionBindings(ownership, mapper, mock(DashboardEvidenceReads.class)), manifests, new WidgetQueryDecoder(mapper));
        return new DashboardTemplatePreparation(new DashboardTaskTemplates(manifests), ownership, catalogue, checks, assembler);
    }

    private TemplateDeviceCatalogue.Device device(String name) {
        var device = new TemplateDeviceCatalogue.Device(UUID.randomUUID(), name, "FRIDGE",
                List.of(new TemplateDeviceCatalogue.Metric("activePowerW", "W"), new TemplateDeviceCatalogue.Metric("temperatureC", "C")),
                List.of(new TemplateDeviceCatalogue.Source(UUID.randomUUID(), "ordinary-source")));
        when(ownership.canReadDevice(eq("Bearer current"), eq(device.id()), eq(actor))).thenReturn(true);
        when(catalogue.read("Bearer current", device.id())).thenReturn(device);
        return device;
    }

    private DashboardTemplatePreparation.Request request(List<UUID> ids) {
        return new DashboardTemplatePreparation.Request(1, "Private review", ids, "LAST_24_HOURS", List.of(), List.of());
    }

    @Test void panelSelectionOrderAndSizesCompileIntoStableResponsiveDefinition() {
        var device = device("Kitchen fridge");
        var panels = List.of(new DashboardTemplateLayout.Panel("temperature-series", "FULL", 6),
                new DashboardTemplateLayout.Panel("temperature", "HALF", 3),
                new DashboardTemplateLayout.Panel("incidents", "HALF", 4));
        var request = new DashboardTemplatePreparation.Request(1, "Preview", List.of(device.id()), "LAST_7_DAYS", List.of(), List.of(), panels);
        var prepared = service().prepare(actor, "Bearer current", "refrigeration", request);
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets()).extracting(DashboardDefinition.Widget::type)
                .containsExactly("time-series", "latest-value", "incident-list");
        var desktop = prepared.definition().layouts().path("desktop");
        assertThat(desktop.get(0).path("w").asInt()).isEqualTo(12);
        assertThat(desktop.get(0).path("h").asInt()).isEqualTo(6);
        assertThat(desktop.get(1).path("y").asInt()).isEqualTo(6);
        assertThat(desktop.get(2).path("x").asInt()).isEqualTo(6);
        assertThat(prepared.definition().layouts().path("tablet").get(2).path("x").asInt()).isEqualTo(3);
        assertThat(prepared.definition().layouts().path("mobile").get(2).path("y").asInt()).isEqualTo(9);
        assertThat(prepared.definition().defaultContext().path("time").path("preset").asText()).isEqualTo("LAST_7_DAYS");
        assertThat(prepared.digest()).isEqualTo(assembler.digest(prepared.definition()));
        assertThat(service().prepare(actor, "Bearer current", "refrigeration", request).digest()).isEqualTo(prepared.digest());
    }

    @Test void invalidPanelSelectionsNeverBecomeAnAlternativeLayout() {
        var device = device("Kitchen fridge");
        List<List<DashboardTemplateLayout.Panel>> invalid = List.of(List.of(),
                List.of(new DashboardTemplateLayout.Panel("invented", "FULL", 4)),
                List.of(new DashboardTemplateLayout.Panel("temperature", "FULL", 4), new DashboardTemplateLayout.Panel("temperature", "HALF", 4)),
                List.of(new DashboardTemplateLayout.Panel("temperature", "QUARTER", 4)),
                List.of(new DashboardTemplateLayout.Panel("temperature", "FULL", 99)));
        for (var panels : invalid) {
            var request = new DashboardTemplatePreparation.Request(1, "Bad", List.of(device.id()), null, List.of(), List.of(), panels);
            assertThatThrownBy(() -> service().prepare(actor, "Bearer current", "refrigeration", request)).isInstanceOf(DashboardFailure.class);
        }
    }

    @Test void preparesAnIndependentPrivateDefinitionWithoutPersistenceOrInventedSamples() throws Exception {
        var device = device("Kitchen fridge");
        var prepared = service().prepare(actor, "Bearer current", "refrigeration", request(List.of(device.id())));
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets()).hasSize(6);
        assertThat(prepared.devices().getFirst()).containsEntry("name", "Kitchen fridge");
        assertThat(mapper.writeValueAsString(prepared.definition())).doesNotContain("publishedRevision", "workspaceId", "accountId", "currentValue", "password");
        assertThat(prepared.definition().widgets().stream().filter(widget -> widget.type().equals("time-series")).toList()).hasSize(2);
        assertThat(service().prepare(actor, "Bearer current", "refrigeration", request(List.of(device.id()))).digest()).isEqualTo(prepared.digest());
    }

    @Test void mixedForeignBindingsFailBeforeAnyCatalogueLabelRead() {
        var allowed = device("Allowed");
        UUID denied = UUID.randomUUID();
        assertThatThrownBy(() -> service().prepare(actor, "Bearer current", "refrigeration", request(List.of(allowed.id(), denied))))
                .isInstanceOfSatisfying(DashboardFailure.class, failure -> assertThat(failure.code()).isEqualTo("NO_ACCESS"));
        verifyNoInteractions(catalogue);
    }

    @Test void missingMetricWrongUnitAndAmbiguousSourceAskInsteadOfDroppingPanels() {
        var device = device("Kitchen fridge");
        when(catalogue.read("Bearer current", device.id())).thenReturn(new TemplateDeviceCatalogue.Device(device.id(), device.name(), device.type(),
                List.of(new TemplateDeviceCatalogue.Metric("temperatureC", "F")), device.sources()));
        var missing = service().prepare(actor, "Bearer current", "refrigeration", request(List.of(device.id())));
        assertThat(missing.status()).isEqualTo("NEEDS_INPUT");
        assertThat(missing.definition()).isNull();
        assertThat(missing.diagnostics().toString()).contains("temperatureC", "activePowerW");
        when(catalogue.read("Bearer current", device.id())).thenReturn(new TemplateDeviceCatalogue.Device(device.id(), device.name(), device.type(), device.metrics(),
                List.of(device.sources().getFirst(), new TemplateDeviceCatalogue.Source(UUID.randomUUID(), "other-source"))));
        assertThat(service().prepare(actor, "Bearer current", "my-devices", request(List.of(device.id()))).status()).isEqualTo("NEEDS_INPUT");
    }

    @Test void selectedMetricSubsetIsExplicitAndUnknownSelectionsNeverEnterDefinition() {
        var device = device("Kitchen fridge");
        var subset = new DashboardTemplatePreparation.Request(1, "Temperature", List.of(device.id()), "LAST_7_DAYS", List.of("temperatureC"), List.of());
        var prepared = service().prepare(actor, "Bearer current", "refrigeration", subset);
        assertThat(prepared.status()).isEqualTo("READY");
        assertThat(prepared.definition().widgets()).noneMatch(widget -> "activePowerW".equals(widget.query().path("metric").asText()));
        var unknown = new DashboardTemplatePreparation.Request(1, "Unknown", List.of(device.id()), null, List.of("made-up"), List.of());
        assertThat(service().prepare(actor, "Bearer current", "refrigeration", unknown).status()).isEqualTo("NEEDS_INPUT");
        var foreign = new DashboardTemplatePreparation.Request(1, "Foreign", List.of(device.id()), null, List.of(), List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service().prepare(actor, "Bearer current", "refrigeration", foreign)).isInstanceOf(DashboardFailure.class);
    }

    @Test void versionDriftAndWidgetExpansionLimitRequireNewReview() {
        var device = device("Kitchen fridge");
        var changed = new DashboardTemplatePreparation.Request(99, "Changed", List.of(device.id()), null, List.of(), List.of());
        assertThat(service().prepare(actor, "Bearer current", "refrigeration", changed).status()).isEqualTo("NEEDS_INPUT");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(device("Fixture " + i).id());
        assertThatThrownBy(() -> service().prepare(actor, "Bearer current", "my-devices", request(ids)))
                .isInstanceOfSatisfying(DashboardFailure.class, failure -> assertThat(failure.code()).isEqualTo("LIMIT_EXCEEDED"));
    }

    @Test void digestIsStableAcrossJsonObjectFieldOrderAndFreshAssemblerInstances() {
        var device = device("Kitchen fridge");
        var prepared = service().prepare(actor, "Bearer current", "refrigeration", request(List.of(device.id())));
        var definition = prepared.definition();
        var reversed = new ArrayList<DashboardDefinition.Widget>();
        for (var widget : definition.widgets()) {
            var query = mapper.createObjectNode();
            var names = new ArrayList<String>(); widget.query().fieldNames().forEachRemaining(names::add);
            Collections.reverse(names); for (var name : names) query.set(name, widget.query().get(name));
            reversed.add(new DashboardDefinition.Widget(widget.id(), widget.type(), widget.typeVersion(), widget.title(), widget.binding(), query, widget.presentation(), widget.navigation(), widget.payload()));
        }
        var same = new DashboardDefinition(definition.schemaVersion(), definition.name(), definition.description(), definition.defaultContext(), definition.sections(), reversed, definition.layouts());
        assertThat(new DashboardTemplateAssembler(new ObjectMapper()).digest(same)).isEqualTo(prepared.digest());
    }
}
