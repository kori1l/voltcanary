package com.voltcanary.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.query.WidgetManifests;
import com.voltcanary.dashboard.query.WidgetQueryDecoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetMapConfigTest {

    @Test
    void requiresExactSourcePerFixedDeviceAndPreservesLegacySingleMap() {
        var mapper = new ObjectMapper();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var decoder = new WidgetQueryDecoder(mapper);
        var manifest = new WidgetManifests().find("vehicle-map", 1).orElseThrow();
        var binding = mapper.valueToTree(
            Map.of("mode", "FIXED", "deviceIds", List.of(first, second))
        );
        var query = mapper.valueToTree(
            Map.of("vehicleSources", Map.of(first, "gps-a", second, "gps-b"))
        );
        var widget = new Widget(
            "fleet",
            "vehicle-map",
            1,
            "Fleet",
            binding,
            query,
            null,
            null,
            null
        );
        assertThat(decoder.config(widget, manifest).vehicleSources()).containsEntry(
            second,
            "gps-b"
        );
        var invalid = new Widget(
            "fleet",
            "vehicle-map",
            1,
            "Fleet",
            binding,
            mapper.valueToTree(Map.of("vehicleSources", Map.of(first, "gps-a"))),
            null,
            null,
            null
        );
        assertThatThrownBy(() -> decoder.config(invalid, manifest)).isInstanceOf(
            DashboardFailure.class
        );
        var single = new Widget(
            "single",
            "vehicle-map",
            1,
            "Car",
            mapper.valueToTree(Map.of("mode", "FIXED", "deviceIds", List.of(first))),
            mapper.valueToTree(Map.of("sourceId", "gps-a")),
            null,
            null,
            null
        );
        assertThat(decoder.config(single, manifest).sourceId()).isEqualTo("gps-a");
    }
}
