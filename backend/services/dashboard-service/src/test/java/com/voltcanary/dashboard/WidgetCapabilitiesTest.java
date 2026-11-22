package com.voltcanary.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.WidgetCapabilities;
import com.voltcanary.dashboard.query.WidgetManifests;
import org.junit.jupiter.api.Test;

class WidgetCapabilitiesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void catalogueDescriptionsAndSchemasCoverEveryOwnedSetting() throws Exception {
        for (var manifest : new WidgetManifests().all()) {
            assertThat(manifest.description()).isNotBlank();
            var capabilities = mapper.valueToTree(manifest.capabilities());
            var schema = capabilities.path("settingsSchema");
            assertThat(schema.path("properties").size()).isEqualTo(
                manifest.capabilities().settings().size()
            );
            for (var setting : manifest.capabilities().settings()) {
                assertThat(
                    schema.at("/properties/" + setting.key() + "/description").asText()
                ).isEqualTo(setting.label());
                var settings = mapper.createObjectNode();
                settings.set(setting.key(), mapper.valueToTree(setting.defaultValue()));
                WidgetCapabilities.validate(manifest.type(), settings, true);
            }
        }
        var mapSchema = mapper.valueToTree(
            WidgetCapabilities.forType("vehicle-map").settingsSchema()
        );
        assertThat(mapSchema.at("/properties/mapFields/maxItems").asInt()).isEqualTo(50);
        var compact = mapper.valueToTree(
            WidgetCapabilities.forType("latest-value").settingsSchema()
        );
        assertThat(compact.at("/properties/compact/type").toString()).contains("boolean", "null");
        assertThat(compact.at("/properties/compact/enum").isMissingNode()).isTrue();
    }

    @Test
    void everyWidgetUsesTheSameGeneralHeaderAndEvidenceSettings() {
        for (var manifest : new WidgetManifests().all()) {
            var general = manifest
                .capabilities()
                .settings()
                .stream()
                .filter(setting -> setting.section().equals("GENERAL"))
                .toList();
            assertThat(general)
                .extracting(WidgetCapabilities.Setting::key)
                .containsExactly("showTitle", "showDetails");
            assertThat(manifest.capabilities().visualizations()).isEqualTo(
                manifest.visualizations()
            );
        }
    }

    @Test
    void validatesActualTypeSpecificSettingsAndDoesNotTreatFalseAsMissing() throws Exception {
        WidgetCapabilities.validate(
            "vehicle-map",
            mapper.readTree("{\"showTitle\":false,\"showMapNotices\":false}"),
            true
        );
        assertThatThrownBy(() ->
            WidgetCapabilities.validate(
                "latest-value",
                mapper.readTree("{\"showMapNotices\":false}"),
                true
            )
        ).isInstanceOf(DashboardFailure.class);
        assertThatThrownBy(() ->
            WidgetCapabilities.validate(
                "vehicle-map",
                mapper.readTree("{\"showTitle\":\"false\"}"),
                true
            )
        ).isInstanceOf(DashboardFailure.class);
        assertThatThrownBy(() ->
            WidgetCapabilities.validate(
                "prediction-evidence",
                mapper.readTree("{\"predictionSort\":\"MADE_UP\"}"),
                true
            )
        ).isInstanceOf(DashboardFailure.class);
        WidgetCapabilities.validate(
            "vehicle-map",
            mapper.readTree("{\"historicalClientSetting\":true}"),
            false
        );
    }

    @Test
    void historyGraphsRejectTextCapabilitiesWhileObservationListsRetainThem() {
        var descriptor = WidgetCapabilities.forType("vehicle-timeline");
        assertThat(descriptor.visualizationValueTypes().get("line")).containsExactly(
            "number",
            "boolean"
        );
        assertThat(descriptor.visualizationValueTypes().get("list")).contains("string");
        assertThat(descriptor.binding().maxDevices()).isEqualTo(1);
    }
}
