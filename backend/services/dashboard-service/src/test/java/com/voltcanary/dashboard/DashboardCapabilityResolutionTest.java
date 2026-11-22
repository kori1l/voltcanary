package com.voltcanary.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voltcanary.dashboard.application.DashboardCapabilityResolution;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDataChoices.Choice;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardCapabilityEvidence;
import com.voltcanary.dashboard.port.DashboardCapabilityEvidence.Field;
import com.voltcanary.dashboard.port.DashboardCapabilityEvidence.Source;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue.Metric;
import com.voltcanary.dashboard.query.WidgetManifests;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardCapabilityResolutionTest {

    private final DashboardOwnership ownership = mock(DashboardOwnership.class);
    private final DashboardCapabilityEvidence evidence = mock(DashboardCapabilityEvidence.class);
    private final DashboardActor actor = new DashboardActor(UUID.randomUUID(), UUID.randomUUID());
    private final UUID device = UUID.randomUUID(),
        source = UUID.randomUUID();
    private final WidgetManifests manifests = new WidgetManifests();
    private final DashboardCapabilityResolution resolver = new DashboardCapabilityResolution(
        ownership,
        evidence,
        manifests
    );

    @BeforeEach
    void permit() {
        when(ownership.canReadDevice(eq("auth"), any(), eq(actor))).thenReturn(true);
    }

    private Field field(String key, String type, boolean observed) {
        return new Field(
            key,
            key,
            type,
            "",
            "recorded",
            observed,
            Instant.parse("2026-10-23T10:00:00Z")
        );
    }

    private void given(List<Source> sources, List<Field> fields, List<Metric> metrics) {
        when(evidence.read("auth", device)).thenReturn(
            new DashboardCapabilityEvidence.Device(
                device,
                "Vehicle",
                "VEHICLE",
                metrics,
                sources,
                fields
            )
        );
    }

    private com.voltcanary.dashboard.domain.DashboardDataChoices.Resolution resolve(
        String purpose
    ) {
        return resolver.resolve(
            actor,
            "auth",
            new DashboardCapabilityResolution.Request(List.of(device), "LAST_1_HOUR", purpose)
        );
    }

    @Test
    void booleanAndTextUseRecordedIdentityAndDescriptorViewRules() {
        given(
            List.of(new Source(source, "recorded", "STALE")),
            List.of(field("doors", "boolean", true), field("stop", "string", true)),
            List.of()
        );
        var result = resolve(null);
        Choice doors = result
            .choices()
            .stream()
            .filter(c -> "doors".equals(c.key()))
            .findFirst()
            .orElseThrow();
        assertThat(doors.family()).isEqualTo("RECORDED_VEHICLE_FIELD");
        assertThat(doors.currentStatus()).isEqualTo("OBSERVED");
        assertThat(doors.sourceFreshness()).isEqualTo("STALE");
        assertThat(doors.historyStatus()).isEqualTo("UNKNOWN");
        assertThat(doors.views()).anySatisfy(v -> {
            assertThat(v.widgetType()).isEqualTo("vehicle-timeline");
            assertThat(v.visualizations()).contains("line", "list");
        });
        Choice stop = result
            .choices()
            .stream()
            .filter(c -> "stop".equals(c.key()))
            .findFirst()
            .orElseThrow();
        assertThat(stop.views())
            .filteredOn(v -> v.widgetType().equals("vehicle-timeline"))
            .allSatisfy(v -> assertThat(v.visualizations()).containsExactly("list"));
        assertThatThrownBy(() ->
            resolver.requireCompatible(
                result,
                "vehicle-timeline",
                List.of(device),
                source,
                null,
                null,
                "stop",
                "line"
            )
        ).isInstanceOf(DashboardFailure.class);
    }

    @Test
    void sourcesAreNotCollapsedAndUnselectedAmbiguityCannotPrepare() {
        UUID other = UUID.randomUUID();
        given(
            List.of(new Source(source, "recorded", "FRESH"), new Source(other, "other", "FRESH")),
            List.of(
                field("doors", "boolean", true),
                new Field("doors", "doors", "boolean", "", "other", true, Instant.now())
            ),
            List.of()
        );
        var result = resolve("HISTORY");
        assertThat(result.diagnostics()).anySatisfy(d ->
            assertThat(d.code()).isEqualTo("AMBIGUOUS_SOURCE")
        );
        assertThatThrownBy(() ->
            resolver.requireCompatible(
                result,
                "vehicle-timeline",
                List.of(device),
                null,
                null,
                null,
                "doors",
                "line"
            )
        )
            .isInstanceOf(DashboardFailure.class)
            .hasMessageContaining("source explicitly");
        resolver.requireCompatible(
            result,
            "vehicle-timeline",
            List.of(device),
            other,
            null,
            null,
            "doors",
            "line"
        );
    }

    @Test
    void metadataTypeDriftInvalidatesOldViewAndChoiceIdentity() {
        given(
            List.of(new Source(source, "recorded", "FRESH")),
            List.of(field("state", "number", true)),
            List.of()
        );
        var old = resolve("HISTORY");
        given(
            List.of(new Source(source, "recorded", "FRESH")),
            List.of(field("state", "string", true)),
            List.of()
        );
        var current = resolve("HISTORY");
        assertThat(current.choices().getFirst().choiceId()).isNotEqualTo(
            old.choices().getFirst().choiceId()
        );
        assertThatThrownBy(() ->
            resolver.requireCompatible(
                current,
                "vehicle-timeline",
                List.of(device),
                source,
                null,
                null,
                "state",
                "line"
            )
        ).isInstanceOf(DashboardFailure.class);
        given(
            List.of(new Source(source, "recorded", "FRESH")),
            List.of(field("state", "boolean", true)),
            List.of()
        );
        var booleanType = resolve("HISTORY");
        resolver.requireCompatible(
            booleanType,
            "vehicle-timeline",
            List.of(device),
            source,
            null,
            null,
            "state",
            "line"
        );
        assertThatThrownBy(() ->
            resolver.requireCompatible(
                booleanType,
                "vehicle-timeline",
                List.of(device),
                source,
                null,
                null,
                "state",
                "line",
                old.choices().getFirst().choiceId()
            )
        ).isInstanceOf(DashboardFailure.class);
    }

    @Test
    void metadataWithoutValueRetainsCapabilityAndDoesNotInventHistorySamples() {
        given(
            List.of(new Source(source, "recorded", "NO_DATA")),
            List.of(field("state", "boolean", false)),
            List.of()
        );
        var result = resolve("CURRENT");
        assertThat(result.choices())
            .singleElement()
            .satisfies(c -> {
                assertThat(c.currentStatus()).isEqualTo("MISSING");
                assertThat(c.historyStatus()).isEqualTo("UNKNOWN");
            });
        resolver.requireCompatible(
            result,
            "vehicle-value",
            List.of(device),
            source,
            null,
            null,
            "state",
            null
        );
    }

    @Test
    void numericMeasurementsNeverProveEnergyOrTariffSemantics() {
        given(List.of(), List.of(), List.of(new Metric("consumption", "W")));
        var result = resolve(null);
        var metric = result
            .choices()
            .stream()
            .filter(c -> c.family().equals("REGISTERED_NUMERIC_METRIC"))
            .findFirst()
            .orElseThrow();
        assertThat(metric.views()).allSatisfy(v ->
            assertThat(v.widgetType()).isIn("latest-value", "time-series", "metric-summary")
        );
        assertThat(
            result
                .choices()
                .stream()
                .filter(c -> c.family().equals("DOMAIN_EVIDENCE"))
                .flatMap(c -> c.views().stream())
        ).allSatisfy(v -> {
            assertThat(v.readiness()).isEqualTo("REQUIRES_CONFIGURATION");
            assertThat(v.requirements()).isNotEmpty();
        });
        assertThatThrownBy(() ->
            resolver.requireCompatible(
                result,
                "time-series",
                List.of(device),
                null,
                "consumption",
                "kW",
                null,
                "line"
            )
        ).isInstanceOf(DashboardFailure.class);
    }

    @Test
    void deniesBeforeAnyEvidenceReadAndPropagatesDependencies() {
        when(ownership.canReadDevice("auth", device, actor)).thenReturn(false);
        assertThatThrownBy(() -> resolve(null))
            .isInstanceOf(DashboardFailure.class)
            .hasMessageContaining("unavailable");
        verifyNoInteractions(evidence);
        when(ownership.canReadDevice("auth", device, actor)).thenReturn(true);
        when(evidence.read("auth", device)).thenThrow(
            new DashboardFailure("DEPENDENCY_UNAVAILABLE", "offline")
        );
        assertThatThrownBy(() -> resolve(null))
            .isInstanceOf(DashboardFailure.class)
            .hasMessage("offline");
    }

    @Test
    void narrowingAndPeriodLimitsDoNotSilentlyTruncate() {
        given(
            List.of(),
            List.of(),
            java.util.stream.IntStream.range(0, 201)
                .mapToObj(i -> new Metric("m" + i, "W"))
                .toList()
        );
        assertThatThrownBy(() -> resolve(null))
            .isInstanceOf(DashboardFailure.class)
            .hasMessageContaining("Narrow");
        assertThatThrownBy(() ->
            resolver.resolve(
                actor,
                "auth",
                new DashboardCapabilityResolution.Request(List.of(device), "ANY", null)
            )
        ).isInstanceOf(DashboardFailure.class);
    }
}
