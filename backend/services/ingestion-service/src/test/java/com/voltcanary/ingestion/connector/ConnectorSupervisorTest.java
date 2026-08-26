package com.voltcanary.ingestion.connector;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterResolver;
import com.voltcanary.ingestion.adapter.api.PollingTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetrySink;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import com.voltcanary.ingestion.pipeline.IngestionResult;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorSupervisorTest {

    private final ConnectorRuntimeProperties properties = properties();
    private final ConnectorScheduler scheduler = new ConnectorScheduler(properties);
    private final ConnectorBackoffPolicy backoffPolicy = new ConnectorBackoffPolicy(properties);
    private final ConnectorHealthManager healthManager = new ConnectorHealthManager();
    private final RecordingPipeline pipeline = new RecordingPipeline();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void pollingSourceRunsOnlyWhenLeaseIsOwned() {
        SourceRuntimeConfig config = config(1L, "ACTIVE", Map.of("pollInterval", "1s"));
        RecordingPollingAdapter adapter = new RecordingPollingAdapter(message(config));
        ManualLeaseManager leaseManager = new ManualLeaseManager();
        ConnectorSupervisor supervisor = supervisor(leaseManager, adapter);

        leaseManager.allowAcquire = true;
        supervisor.reconcile(List.of(config));
        supervisor.tick();

        assertThat(adapter.polls).isEqualTo(1);
        assertThat(pipeline.ingested).hasSize(1);
        assertThat(supervisor.state(config.dataSourceId())).isEqualTo(ConnectorRuntimeState.RUNNING);

        clock.advance(Duration.ofSeconds(1));
        leaseManager.allowRenew = false;
        supervisor.tick();

        assertThat(adapter.polls).isEqualTo(1);
        assertThat(supervisor.state(config.dataSourceId())).isEqualTo(ConnectorRuntimeState.LEASE_LOST);
        assertThat(healthManager.snapshot(config.sourceId()).state()).isEqualTo(ConnectorRuntimeState.LEASE_LOST);
    }

    @Test
    void configVersionChangeRestartsSubscriptionAndDisabledSourceStopsIt() {
        SourceRuntimeConfig first = config(1L, "ACTIVE", Map.of());
        SourceRuntimeConfig second = new SourceRuntimeConfig(
                first.sourceId(),
                first.dataSourceId(),
                first.accountId(),
                first.deviceId(),
                first.adapterType(),
                first.protocol(),
                first.transport(),
                "ACTIVE",
                first.mappingSchemaVersion(),
                first.adapterConfigSchemaVersion(),
                first.mapping(),
                Map.of("pollInterval", "2s"),
                first.credentialReferences(),
                first.homeRegion(),
                2L);
        RecordingSubscriptionAdapter adapter = new RecordingSubscriptionAdapter();
        ConnectorSupervisor supervisor = supervisor(new ManualLeaseManager(), adapter);

        supervisor.reconcile(List.of(first));
        supervisor.tick();
        supervisor.reconcile(List.of(second));
        supervisor.tick();
        supervisor.reconcile(List.of());

        assertThat(adapter.starts).isEqualTo(2);
        assertThat(adapter.stops).isEqualTo(2);
        assertThat(supervisor.state(first.dataSourceId())).isEqualTo(ConnectorRuntimeState.STOPPED);
    }

    @Test
    void failedSourceBacksOffWithoutStoppingOtherSources() {
        SourceRuntimeConfig failing = config(1L, "ACTIVE", Map.of("pollInterval", "1s"));
        SourceRuntimeConfig healthy = new SourceRuntimeConfig(
                "healthy-source",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdapterType.GENERIC_HTTP_POLLING,
                ProtocolType.HTTP,
                TransportType.HTTP,
                "ACTIVE",
                "mapping.v1",
                "adapter.v1",
                Map.of(),
                Map.of("pollInterval", "1s"),
                Map.of(),
                "default",
                1L);
        SelectivePollingAdapter adapter = new SelectivePollingAdapter(failing.sourceId(), message(healthy));
        ConnectorSupervisor supervisor = supervisor(new ManualLeaseManager(), adapter);

        supervisor.reconcile(List.of(failing, healthy));
        supervisor.tick();

        assertThat(supervisor.state(failing.dataSourceId())).isEqualTo(ConnectorRuntimeState.DEGRADED);
        assertThat(supervisor.state(healthy.dataSourceId())).isEqualTo(ConnectorRuntimeState.RUNNING);
        assertThat(pipeline.ingested).hasSize(1);
        assertThat(healthManager.snapshot(failing.sourceId()).nextAttemptAt()).isAfter(clock.instant());
    }

    private ConnectorSupervisor supervisor(ConnectorLeaseManager leaseManager, TelemetryAdapter adapter) {
        IngestionAdapterResolver resolver = ignored -> Optional.of(adapter);
        return new ConnectorSupervisor(
                leaseManager,
                resolver,
                scheduler,
                backoffPolicy,
                healthManager,
                pipeline,
                clock);
    }

    private static ConnectorRuntimeProperties properties() {
        ConnectorRuntimeProperties properties = new ConnectorRuntimeProperties();
        properties.setDefaultPollingInterval(Duration.ofSeconds(1));
        properties.setInitialBackoff(Duration.ofMillis(100));
        properties.setMaxBackoff(Duration.ofSeconds(1));
        properties.setJitterRatio(0.0);
        return properties;
    }

    private static SourceRuntimeConfig config(long version, String status, Map<String, Object> adapterConfig) {
        return new SourceRuntimeConfig(
                "source-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdapterType.GENERIC_HTTP_POLLING,
                ProtocolType.HTTP,
                TransportType.HTTP,
                status,
                "mapping.v1",
                "adapter.v1",
                Map.of(),
                adapterConfig,
                Map.of(),
                "default",
                version);
    }

    private static IncomingTelemetryMessage message(SourceRuntimeConfig config) {
        return new IncomingTelemetryMessage(
                config.sourceId(),
                config.deviceId(),
                config.accountId(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("temperature", 4.2),
                new TelemetryOrigin(
                        config.adapterType(),
                        config.protocol(),
                        config.transport(),
                        "test-active-adapter",
                        Map.of()),
                IngestionContext.empty(),
                false);
    }

    private static AdapterManifest manifest(AdapterCapability capability) {
        return new AdapterManifest(
                "test-active-adapter",
                AdapterType.GENERIC_HTTP_POLLING,
                "Test active adapter",
                TransportType.HTTP,
                ProtocolType.HTTP,
                AdapterCapabilities.of(capability),
                "v1");
    }

    private static final class RecordingPollingAdapter implements PollingTelemetryAdapter {
        private final IncomingTelemetryMessage message;
        private int polls;

        private RecordingPollingAdapter(IncomingTelemetryMessage message) {
            this.message = message;
        }

        @Override
        public List<IncomingTelemetryMessage> poll(SourceRuntimeConfig sourceRuntimeConfig) {
            polls++;
            return List.of(message);
        }

        @Override
        public AdapterManifest manifest() {
            return ConnectorSupervisorTest.manifest(AdapterCapability.POLLING);
        }
    }

    private static final class SelectivePollingAdapter implements PollingTelemetryAdapter {
        private final String failingSourceId;
        private final IncomingTelemetryMessage message;

        private SelectivePollingAdapter(String failingSourceId, IncomingTelemetryMessage message) {
            this.failingSourceId = failingSourceId;
            this.message = message;
        }

        @Override
        public List<IncomingTelemetryMessage> poll(SourceRuntimeConfig sourceRuntimeConfig) {
            if (failingSourceId.equals(sourceRuntimeConfig.sourceId())) {
                throw new IllegalStateException("source timeout");
            }
            return List.of(message);
        }

        @Override
        public AdapterManifest manifest() {
            return ConnectorSupervisorTest.manifest(AdapterCapability.POLLING);
        }
    }

    private static final class RecordingSubscriptionAdapter implements SubscriptionTelemetryAdapter {
        private int starts;
        private int stops;

        @Override
        public void startSubscription(SourceRuntimeConfig sourceRuntimeConfig, SubscriptionTelemetrySink sink) {
            starts++;
        }

        @Override
        public void stopSubscription(SourceRuntimeConfig sourceRuntimeConfig) {
            stops++;
        }

        @Override
        public AdapterManifest manifest() {
            return ConnectorSupervisorTest.manifest(AdapterCapability.SUBSCRIPTION);
        }
    }

    private static final class RecordingPipeline implements TelemetryIngestionPipeline {
        private final List<IncomingTelemetryMessage> ingested = new java.util.ArrayList<>();

        @Override
        public IngestionResult ingest(IncomingTelemetryMessage message) {
            ingested.add(message);
            return new IngestionResult(true, UUID.randomUUID(), "accepted");
        }
    }

    private static final class ManualLeaseManager implements ConnectorLeaseManager {
        private boolean allowAcquire = true;
        private boolean allowRenew = true;

        @Override
        public boolean acquire(UUID dataSourceId) {
            return allowAcquire;
        }

        @Override
        public boolean renew(UUID dataSourceId) {
            return allowRenew;
        }

        @Override
        public void release(UUID dataSourceId) {
        }

        @Override
        public boolean isOwned(UUID dataSourceId) {
            return allowAcquire && allowRenew;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
