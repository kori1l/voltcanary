package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.ProcessingContext;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.variable.VariableDashboardSeries;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@org.junit.jupiter.api.Tag("docker")
class VariableTelemetryPostgresTest extends VariableTelemetryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor(
            "postgres"
        )
    );

    @Autowired
    VariableDashboardSeries dashboard;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void simultaneousDuplicateDeliveriesCommitOneReceiptAndOneOutput() throws Exception {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        var incoming = event(snapshot, Instant.now().minusSeconds(2), false);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> handler.process(incoming));
            var second = workers.submit(() -> handler.process(incoming));
            assertThat(
                List.of(
                    first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)
                )
            ).containsExactlyInAnyOrder(0, 2);
        }
        assertThat(
            jdbc.queryForObject(
                "select count(*) from variable_measurement_lineage where source_event_id=?",
                Integer.class,
                incoming.eventId()
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from telemetry_event_outbox where event_key=?",
                Integer.class,
                "variables:" + incoming.eventId()
            )
        ).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackInputsLineageAndReceiptBeforeSuccessfulRetry() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        var incoming = event(snapshot, Instant.now().minusSeconds(2), false);
        jdbc.execute(
            "CREATE FUNCTION variable_test_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.topic='telemetry.measurements.accepted.v1' AND NEW.message_key='" +
                snapshot.deviceId() +
                "' THEN RAISE EXCEPTION 'injected variable outbox failure'; END IF; RETURN NEW; END $$"
        );
        jdbc.execute(
            "CREATE TRIGGER variable_test_outbox_failure BEFORE INSERT ON telemetry_event_outbox FOR EACH ROW EXECUTE FUNCTION variable_test_reject_outbox()"
        );
        try {
            assertThatThrownBy(() -> handler.process(incoming)).isInstanceOf(
                RuntimeException.class
            );
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from telemetry.measurements where device_id=?",
                    Integer.class,
                    snapshot.deviceId()
                )
            ).isZero();
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from telemetry_readings where device_id=?",
                    Integer.class,
                    snapshot.deviceId()
                )
            ).isZero();
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from variable_measurement_lineage where source_event_id=?",
                    Integer.class,
                    incoming.eventId()
                )
            ).isZero();
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from processed_variable_events where event_id=?",
                    Integer.class,
                    incoming.eventId()
                )
            ).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER variable_test_outbox_failure ON telemetry_event_outbox");
            jdbc.execute("DROP FUNCTION variable_test_reject_outbox()");
        }
        assertThat(handler.process(incoming)).isEqualTo(2);
    }

    @Test
    void measuredFixtureCostQueryPlanAndRawCursorAreExplicit() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 23.5);
        bundles.apply(snapshot);
        Instant start = Instant.now().minusSeconds(100);
        long started = System.nanoTime();
        for (int index = 0; index < 50; index++) handler.process(
            event(snapshot, start.plusSeconds(index), false)
        );
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        UUID variable = snapshot.bindings().getFirst().contract().variableId();
        assertThat(
            queries
                .latest(snapshot.deviceId(), snapshot.dataSourceId(), variable)
                .points()
                .getFirst()
                .value()
        ).isZero();
        var cost = jdbc.queryForMap(
            "select count(*) sample_count,avg(pg_column_size(l)) lineage_row_bytes,avg(octet_length(input_snapshot_json)) retained_input_bytes from variable_measurement_lineage l where variable_id=?",
            variable
        );
        var outputCost = jdbc.queryForMap(
            "select count(*) sample_count,avg(pg_column_size(m)) measurement_row_bytes from telemetry.measurements m where device_id=? and metric_name like 'var_%'",
            snapshot.deviceId()
        );
        assertThat(((Number) cost.get("sample_count")).intValue()).isEqualTo(50);
        assertThat(((Number) outputCost.get("sample_count")).intValue()).isEqualTo(50);
        String plan = jdbc.queryForObject(
            "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) select m.metric_value from variable_measurement_lineage l join telemetry.measurements m on m.device_id=l.device_id and m.source_id=l.external_source_id and m.recorded_at=l.recorded_at and m.metric_name=l.metric_token where l.device_id=? and l.data_source_id=? and l.variable_id=? order by l.recorded_at limit 7",
            String.class,
            snapshot.deviceId(),
            snapshot.dataSourceId(),
            variable
        );
        assertThat(plan).contains("Actual Rows", "Execution Time");
        Instant cursor = null;
        int total = 0;
        do {
            var page = queries.read(
                snapshot.deviceId(),
                snapshot.dataSourceId(),
                variable,
                start.minusSeconds(1),
                Instant.now(),
                7,
                cursor,
                0,
                "AVG"
            );
            total += page.points().size();
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(total).isEqualTo(50);
        System.out.println(
            "Variable fixture: samples=50 one input/one output; processingMs=" +
                elapsedMs +
                "; lineage=" +
                cost +
                "; output=" +
                outputCost +
                "; queryPlan=" +
                plan
        );
    }

    @Test
    void dashboardBinsPreserveRealSpikeTimeAndSourceIdentityWithoutWriting() {
        UUID account = UUID.randomUUID(),
            device = UUID.randomUUID(),
            source = UUID.randomUUID(),
            variable = UUID.randomUUID();
        var contract = new VariableContract(
            variable,
            1,
            "rawTemperature",
            "Raw temperature",
            "OBSERVED",
            "C",
            "v1",
            Map.of("x", new VariableContract.Input("C", null, null)),
            VariableExpression.input("x"),
            null,
            null
        );
        var bundle = new SourceProcessingConfigEvent(
            UUID.randomUUID(),
            "v1",
            account,
            device,
            source,
            "source-" + source,
            1,
            "a".repeat(64),
            true,
            List.of(new SourceProcessingConfigEvent.Binding(contract, Map.of("x", "raw"))),
            Instant.now(),
            "device-service"
        );
        bundles.apply(bundle);
        Instant start = Instant.now()
            .minusSeconds(120)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (int index = 0; index < 3; index++) {
            handler.process(
                new TelemetryReceivedEvent(
                    UUID.randomUUID(),
                    device,
                    bundle.sourceId(),
                    start.plusSeconds(index),
                    Map.of(
                        "configuredFields",
                        Map.of(
                            "raw",
                            Map.of(
                                "value",
                                index == 1 ? 45 : 20,
                                "type",
                                "number",
                                "label",
                                "Raw",
                                "unit",
                                "C",
                                "display",
                                true
                            )
                        )
                    ),
                    Instant.now(),
                    "v1",
                    null,
                    "ingestion-service",
                    account,
                    start.plusSeconds(index),
                    new ProcessingContext(source, 1, bundle.digest(), "v1")
                )
            );
        }
        String token = "var_" + variable.toString().replace("-", "");
        var selection = new DashboardOwnerReadRequest.Series(device, token, variable, source);
        var request = new DashboardOwnerReadRequest(
            UUID.randomUUID(),
            account,
            List.of(device),
            new DashboardOwnerReadRequest.TimeFrame(
                start.minusSeconds(60),
                start.plusSeconds(3600),
                Instant.now(),
                null,
                null
            ),
            Set.of(token),
            List.of(device),
            20,
            0,
            null,
            List.of(selection),
            null,
            Set.of("series")
        );
        long before = jdbc.queryForObject(
            "select count(*) from telemetry.measurements",
            Long.class
        );
        var result = dashboard.read(request, selection);
        assertThat(new ObjectMapper().valueToTree(result).toString()).contains(
            "45.0",
            variable.toString(),
            source.toString(),
            start.plusSeconds(1).toString()
        );
        assertThat(
            jdbc.queryForObject("select count(*) from telemetry.measurements", Long.class)
        ).isEqualTo(before);
    }
}
