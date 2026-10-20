package com.voltcanary.telemetry;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.producer.TelemetryDeadLetterPublisher;
import com.voltcanary.telemetry.producer.TelemetryValidatedPublisher;
import com.voltcanary.telemetry.service.PositionReads;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "app.telemetry.validation.redis-rate-limit-enabled=false", "app.telemetry.reconciliation.enabled=false",
        "app.telemetry.validation.max-past-age=30d", "management.health.redis.enabled=false"})
class VehiclePositionsTest {
    @Autowired TelemetryEventProcessor processor;
    @Autowired PositionReads reads;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TelemetryValidatedPublisher publisher;
    @MockitoBean TelemetryDeadLetterPublisher deadLetters;

    @Autowired com.voltcanary.telemetry.service.TelemetryFieldReads fieldReads;
    @Autowired com.voltcanary.telemetry.service.TelemetryQueryService queries;

    @Test void configuredFieldsAreStoredWithPositionsAndCustomNumericCharts() {
        UUID device = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var configured = Map.of(
                "acceleration", Map.of("value", 0, "type", "number", "label", "Acceleration", "unit", "m/s2", "display", true),
                "doors", Map.of("value", false, "type", "boolean", "label", "Doors", "unit", "", "display", true));
        assertThat(processor.process(event(device, "configured", at, Map.of("latitude", 60.1, "longitude", 25.1, "configuredFields", configured)))).isEqualTo(4);
        assertThat(reads.latest(device, "configured", at.plusSeconds(1)).position().fields().get("doors").get("value")).isEqualTo(false);
        assertThat(fieldReads.latest(device, "configured")).extracting(com.voltcanary.telemetry.service.TelemetryFieldReads.Field::key).containsExactlyInAnyOrder("acceleration", "doors");
        assertThat(queries.getMetrics(device)).extracting(com.voltcanary.telemetry.dto.TelemetryMetricResponse::name).contains("acceleration");
        assertThat(queries.chart(device, "acceleration", at.minusSeconds(1), at.plusSeconds(1), null).points()).hasSize(1);
        assertThat(queries.getMetrics(device).stream().filter(metric -> metric.name().equals("doors")).findFirst().orElseThrow().type()).isEqualTo("boolean");
        assertThat(queries.chart(device, "doors", at.minusSeconds(1), at.plusSeconds(1), null).points().getFirst().value()).isZero();
        var next = Map.of("doors", Map.of("value", true, "type", "boolean", "label", "Doors", "unit", "", "display", true));
        processor.process(event(device, "configured", at.plusSeconds(1), Map.of("configuredFields", next)));
        assertThat(queries.chart(device, "doors", at.minusSeconds(1), at.plusSeconds(2), null).points()).extracting(com.voltcanary.telemetry.dto.TelemetryChartPointResponse::value).containsExactly(0.0,1.0);
    }

    @Test void storesOnlyAtomicAcceptedPositionsAndHonorsSourceTimeAndDuplicates() {
        UUID device = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var event = event(device, "gps", at, Map.of("latitude", -44.8, "longitude", -20.4, "speedKmh", 0));
        assertThat(processor.process(event)).isEqualTo(3);
        assertThat(processor.process(event)).isZero();
        processor.process(event(device, "gps", at.minusSeconds(5), Map.of("latitude", 44.7, "longitude", 20.3)));
        processor.process(event(device, "gps", at.plusSeconds(5), Map.of("latitude", 91, "longitude", 20.3)));
        processor.process(event(device, "gps", at.plusSeconds(10), Map.of("latitude", 44.7)));
        processor.process(event(device, "other", at.plusSeconds(10), Map.of("latitude", 44.9, "longitude", 20.5)));
        var latest = reads.latest(device, "gps", at.plusSeconds(31));
        assertThat(latest.freshness()).isEqualTo("STALE");
        assertThat(latest.position().latitude()).isEqualTo(-44.8);
        assertThat(latest.position().speedKmh()).isZero();
        assertThat(reads.latest(device, "gps", at.minusSeconds(1)).position().latitude()).isEqualTo(44.7);
        assertThat(reads.history(device, "gps", at.minusSeconds(5), at.plusSeconds(15)).points()).hasSize(1);
        assertThat(reads.latest(UUID.randomUUID(), "gps", at).freshness()).isEqualTo("NO_DATA");
    }

    @Test void boundedHistoryIsNewestAscendingAndRangeDoesNotExpand() {
        UUID device = UUID.randomUUID();
        Instant end = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for (int i = 0; i < 1005; i++) {
            Instant at = end.minusSeconds(i);
            jdbc.update("insert into telemetry.positions (reading_id,event_id,device_id,source_id,recorded_at,received_at,latitude,longitude,speed_kmh,heading_deg,quality) values (?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), UUID.randomUUID(),
                    device, "gps", java.sql.Timestamp.from(at), java.sql.Timestamp.from(at), 44.8, 20.4, null, null, "OK");
        }
        var history = reads.history(device, "gps", end.minusSeconds(2000), end);
        assertThat(history.truncated()).isTrue();
        assertThat(history.returnedCount()).isEqualTo(1000);
        assertThat(history.points().getFirst().recordedAt()).isEqualTo(end.minusSeconds(999));
        assertThat(history.points().getLast().recordedAt()).isEqualTo(end);
        assertThatThrownBy(() -> reads.history(device, "gps", end.minus(Duration.ofHours(25)), end)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void recordingsCursorKeepsAtomicVehicleValuesAndDoesNotSkipBoundarySamples() {
        UUID device = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for (int i=0; i<5; i++) processor.process(event(device, "gps", at.plusSeconds(i*5), Map.of(
                "latitude", 44.8, "longitude", 20.4, "ignition", 0, "fuelPercent", 0, "odometerKm", 123.0)));
        var first = reads.recordings(device, "gps", at.minusSeconds(1), at.plusSeconds(30), null, 2);
        assertThat(first.points()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.points().getFirst().ignition()).isZero();
        assertThat(first.points().getFirst().fuelPercent()).isZero();
        assertThat(first.points().getFirst().odometerKm()).isEqualTo(123);
        var second = reads.recordings(device, "gps", at.minusSeconds(1), at.plusSeconds(30), first.nextCursor(), 2);
        assertThat(second.points().getFirst().recordedAt()).isEqualTo(at.plusSeconds(10));
        var third = reads.recordings(device, "gps", at.minusSeconds(1), at.plusSeconds(30), second.nextCursor(), 2);
        assertThat(third.points()).hasSize(1);
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
        assertThatThrownBy(() -> reads.recordings(device,"gps",at,at.plusSeconds(60),at.minusSeconds(1),2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> reads.recordings(device,"gps",at,at.plus(Duration.ofDays(32)),null,2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private TelemetryReceivedEvent event(UUID device, String source, Instant at, Map<String, Object> metrics) {
        return new TelemetryReceivedEvent(UUID.randomUUID(), device, source, at, metrics, Instant.now());
    }

    @Test void preservesJourneyContextInHistoryAndRawReadingsWithoutCarryingForwardMissingFields() {
        UUID device=UUID.randomUUID();
        Instant at=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        processor.process(event(device,"hsl",at,Map.of("latitude",60.18,"longitude",24.92)));
        var context=Map.<String,Object>of("routeId","1003","routeShortName","3","destination","Käpylä",
                "nextStopId","1234567","scheduleDeviationSeconds",-45,"doorsOpen",false);
        processor.process(event(device,"hsl",at.plusSeconds(5),Map.of("latitude",60.18,"longitude",24.93,"transport",context)));
        var latest=reads.latest(device,"hsl",at.plusSeconds(6));
        assertThat(latest.position().transport().destination()).isEqualTo("Käpylä");
        assertThat(latest.position().transport().doorsOpen()).isFalse();
        assertThat(latest.position().transport().scheduleDeviationSeconds()).isEqualTo(-45);
        assertThat(jdbc.queryForObject("select metrics_json from telemetry_readings where device_id=? and recorded_at=?",String.class,device,java.sql.Timestamp.from(at.plusSeconds(5))))
                .contains("transport","Käpylä");
        processor.process(event(device,"hsl",at.plusSeconds(10),Map.of("latitude",60.18,"longitude",24.94)));
        assertThat(reads.latest(device,"hsl",at.plusSeconds(11)).position().transport()).isNull();
        processor.process(event(device,"hsl",at.plusSeconds(15),Map.of("latitude",60.18,"longitude",24.95,"transport",Map.of("unknown",true))));
        var history=reads.history(device,"hsl",at.minusSeconds(1),at.plusSeconds(20));
        assertThat(history.points()).hasSize(3);
        assertThat(history.points().get(1).transport().routeShortName()).isEqualTo("3");
    }
}
