package com.voltcanary.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceBindingSchemaEvent;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.observation.ObservationCapture;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclSourceBindingProfile;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Testcontainers
@SpringBootTest(properties = {"app.kafka.consume-enabled=true", "app.kafka.publish-enabled=true",
        "app.observations.enabled=true", "app.observations.consume-enabled=true",
        "app.telemetry.authorization.enabled=false", "app.telemetry.validation.redis-rate-limit-enabled=false",
        "app.telemetry.reconciliation.enabled=false", "management.health.redis.enabled=false"})
class ObservationKafkaPostgresTest {
    private static final UUID PRODUCER_EPOCH = UUID.randomUUID();
    @Autowired com.voltcanary.telemetry.observation.SourcePositionProgress progress;
    static final UUID BINDING = UUID.randomUUID(), DEVICE = UUID.randomUUID(), ACCOUNT = UUID.randomUUID(), FIELD = UUID.randomUUID();
    @Container static final RedpandaContainer KAFKA = new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"));
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "observations-" + BINDING);
        registry.add("app.observations.binding-ids", BINDING::toString);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.voltcanary.telemetry.observation.ObservationReads reads;

    @Test void delayedSchemaRawReplayAndSameTimestampTransitionsReachTheBrokerExactlyByFactIdentity() throws Exception {
        var definition = mapper.createObjectNode().put("id", BINDING.toString()).put("schemaRevision", 1)
                .put("profile", VclSourceBindingProfile.VERSION).put("configurationDigest", "a".repeat(64));
        definition.putArray("fields").addObject().put("id", FIELD.toString()).put("type", "BOOLEAN").put("label", "Switch").put("nullable", true);
        definition.putArray("fieldMappings").addObject().put("fieldId", FIELD.toString()).put("sourceKey", "switch")
                .put("path", "switch").put("sourceType", "boolean").put("sourceUnit", "boolean").put("vclReady", true);
        definition.putArray("unsupportedFields");
        String canonical = VclCanonical.encode(definition);
        var pin = new SourceBindingSchemaPin(BINDING, ACCOUNT, DEVICE, "source-" + BINDING, 1, UUID.randomUUID(), VclCanonical.sha256(canonical));
        var schema = new SourceBindingSchemaEvent(UUID.randomUUID(), "v1", pin, canonical, true, Instant.now(), "device-service");
        Instant time = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        var first = event(pin, time, false); var second = event(pin, time, true);
        var producerSettings = new Properties();
        producerSettings.put("bootstrap.servers", KAFKA.getBootstrapServers());
        producerSettings.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerSettings.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (var producer = new KafkaProducer<String, String>(producerSettings)) {
            producer.send(new ProducerRecord<>("telemetry.raw.v1", DEVICE.toString(), mapper.writeValueAsString(first))).get(10, TimeUnit.SECONDS);
            awaitCount("SELECT COUNT(*) FROM cfg_observation_input WHERE status='PENDING'", 1);
            producer.send(new ProducerRecord<>("source.binding-schema.v1", BINDING + ":1", mapper.writeValueAsString(schema.envelope()))).get(10, TimeUnit.SECONDS);
            awaitCount("SELECT COUNT(*) FROM cfg_observation", 1);
            producer.send(new ProducerRecord<>("telemetry.raw.v1", DEVICE.toString(), mapper.writeValueAsString(first))).get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>("telemetry.raw.v1", DEVICE.toString(), mapper.writeValueAsString(second))).get(10, TimeUnit.SECONDS);
            awaitCount("SELECT COUNT(*) FROM cfg_observation", 2);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cfg_observation_input WHERE status='PENDING'", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT head_checkpoint FROM cfg_observation_stream WHERE binding_id=?", Long.class, BINDING));
        var consumerSettings = new Properties();
        consumerSettings.put("bootstrap.servers", KAFKA.getBootstrapServers());
        consumerSettings.put("group.id", "observation-evidence-" + UUID.randomUUID());
        consumerSettings.put("auto.offset.reset", "earliest");
        consumerSettings.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerSettings.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        var facts = new HashMap<String, JsonNode>();
        try (var consumer = new KafkaConsumer<String, String>(consumerSettings)) {
            consumer.subscribe(java.util.List.of(ObservationCapture.TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (facts.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    var envelope = mapper.readTree(record.value());
                    assertEquals("ObservationAccepted", envelope.path("eventType").asText());
                    assertEquals(ACCOUNT.toString(), envelope.path("accountId").asText());
                    facts.put(envelope.path("eventId").asText(), envelope.path("payload"));
                }
            }
        }
        assertEquals(java.util.Set.of(first.eventId().toString(), second.eventId().toString()), facts.keySet());
        assertFalse(facts.get(first.eventId().toString()).at("/fields/0/value").asBoolean());
        assertEquals(true, facts.get(second.eventId().toString()).at("/fields/0/value").asBoolean());
        for (var fact : facts.values()) {
            assertEquals("accepted-boolean-text-2", fact.path("profile").asText());
            assertEquals(jdbc.queryForObject("SELECT input_generation FROM cfg_observation_stream WHERE binding_id=?", UUID.class, BINDING).toString(),
                    fact.path("inputGeneration").asText());
            assertEquals("UNKNOWN", fact.path("upstreamCoverage").asText());
            assertFalse(fact.path("negativeEvidenceReady").asBoolean());
            assertFalse(fact.path("ruleExecutionReady").asBoolean());
        }
        var ownerPin = new com.voltcanary.vcl.VclObservationReadiness.Pin(pin.bindingId(), pin.accountId(), pin.deviceId(), pin.externalSourceId(),
                pin.schemaRevision(), pin.snapshotId(), pin.definitionDigest());
        UUID generation = jdbc.queryForObject("SELECT input_generation FROM cfg_observation_stream WHERE binding_id=?", UUID.class, BINDING);
        var page = reads.read(new com.voltcanary.vcl.VclObservationRead(ownerPin, generation, "0", "2", 128));
        var decoded = com.voltcanary.vcl.VclObservationPage.parse(page.bytes());
        assertEquals(2, decoded.observations().size());
        assertEquals(facts.get(first.eventId().toString()), decoded.observations().getFirst());
        assertEquals(facts.get(second.eventId().toString()), decoded.observations().getLast());
        assertFalse(decoded.ruleExecutionReady());
        var evidence = progress.read(ownerPin, PRODUCER_EPOCH);
        assertEquals("1", evidence.contiguousThrough());
        assertEquals("3", evidence.highestObserved());
        assertEquals(true, evidence.gapPresent());
        assertEquals("UNKNOWN", evidence.upstreamCoverage());
    }
    private TelemetryReceivedEvent event(SourceBindingSchemaPin pin, Instant time, boolean state) {
        return new TelemetryReceivedEvent(UUID.randomUUID(), DEVICE, pin.externalSourceId(), time,
                Map.of("configuredFields", Map.of("switch", Map.of("value", state, "type", "boolean", "label", "Switch", "unit", "boolean", "display", true))),
                time.plusSeconds(1), "v1", null, "ingestion-service", ACCOUNT, time, null, pin,
                new com.voltcanary.common.events.telemetry.SourcePosition(PRODUCER_EPOCH, state ? "3" : "1"));
    }
    private void awaitCount(String sql, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Integer.class) == expected) return;
            Thread.sleep(100);
        }
        assertEquals(expected, jdbc.queryForObject(sql, Integer.class));
    }
}
