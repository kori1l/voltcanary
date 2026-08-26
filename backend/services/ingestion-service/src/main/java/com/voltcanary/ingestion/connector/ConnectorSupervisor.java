package com.voltcanary.ingestion.connector;

import com.voltcanary.ingestion.adapter.api.IngestionAdapterResolver;
import com.voltcanary.ingestion.adapter.api.PollingTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetrySink;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConnectorSupervisor {

    private final Map<UUID, ConnectorWorker> workers = new ConcurrentHashMap<>();
    private final ConnectorLeaseManager leaseManager;
    private final IngestionAdapterResolver adapterResolver;
    private final ConnectorScheduler scheduler;
    private final ConnectorBackoffPolicy backoffPolicy;
    private final ConnectorHealthManager healthManager;
    private final TelemetryIngestionPipeline pipeline;
    private final Clock clock;

    @Autowired
    public ConnectorSupervisor(
            ConnectorLeaseManager leaseManager,
            IngestionAdapterResolver adapterResolver,
            ConnectorScheduler scheduler,
            ConnectorBackoffPolicy backoffPolicy,
            ConnectorHealthManager healthManager,
            TelemetryIngestionPipeline pipeline) {
        this(leaseManager, adapterResolver, scheduler, backoffPolicy, healthManager, pipeline, Clock.systemUTC());
    }

    ConnectorSupervisor(
            ConnectorLeaseManager leaseManager,
            IngestionAdapterResolver adapterResolver,
            ConnectorScheduler scheduler,
            ConnectorBackoffPolicy backoffPolicy,
            ConnectorHealthManager healthManager,
            TelemetryIngestionPipeline pipeline,
            Clock clock) {
        this.leaseManager = leaseManager;
        this.adapterResolver = adapterResolver;
        this.scheduler = scheduler;
        this.backoffPolicy = backoffPolicy;
        this.healthManager = healthManager;
        this.pipeline = pipeline;
        this.clock = clock;
    }

    public void reconcile(List<SourceRuntimeConfig> desiredSources) {
        Set<UUID> desiredIds = new HashSet<>();
        for (SourceRuntimeConfig config : desiredSources) {
            desiredIds.add(config.dataSourceId());
            workers.compute(config.dataSourceId(), (ignored, existing) -> {
                if (existing == null || existing.config.version() != config.version()) {
                    if (existing != null) {
                        existing.stop();
                    }
                    return new ConnectorWorker(config);
                }
                return existing;
            });
        }
        workers.entrySet().removeIf(entry -> {
            if (desiredIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().stop();
            return true;
        });
    }

    public void tick() {
        Instant now = clock.instant();
        for (ConnectorWorker worker : workers.values()) {
            worker.tick(now);
        }
    }

    public ConnectorRuntimeState state(UUID dataSourceId) {
        ConnectorWorker worker = workers.get(dataSourceId);
        return worker == null ? ConnectorRuntimeState.STOPPED : worker.state;
    }

    private final class ConnectorWorker implements SubscriptionTelemetrySink {

        private final SourceRuntimeConfig config;
        private ConnectorRuntimeState state = ConnectorRuntimeState.STOPPED;
        private Instant nextAttemptAt = Instant.EPOCH;
        private int consecutiveFailures;
        private boolean subscriptionStarted;
        private ConnectorLease lease;

        private ConnectorWorker(SourceRuntimeConfig config) {
            this.config = config;
            healthManager.starting(config.sourceId());
        }

        private void tick(Instant now) {
            if (nextAttemptAt.isAfter(now)) {
                return;
            }
            Optional<ConnectorLease> acquired = lease == null || !leaseManager.isCurrent(lease)
                    ? leaseManager.acquire(config.dataSourceId())
                    : leaseManager.renew(lease);
            if (acquired.isEmpty()) {
                leaseLost(now);
                return;
            }
            lease = acquired.get();
            Optional<ConnectorLease> renewed = leaseManager.renew(lease);
            if (renewed.isEmpty()) {
                leaseLost(now);
                return;
            }
            lease = renewed.get();
            Optional<TelemetryAdapter> resolved = adapterResolver.resolve(config);
            if (resolved.isEmpty()) {
                fail(now, new IllegalStateException("No adapter registered for " + config.adapterType()));
                return;
            }
            TelemetryAdapter adapter = resolved.get();
            try {
                if (adapter instanceof PollingTelemetryAdapter pollingAdapter) {
                    runPoll(pollingAdapter, now);
                } else if (adapter instanceof SubscriptionTelemetryAdapter subscriptionAdapter) {
                    runSubscription(subscriptionAdapter, now);
                } else {
                    fail(now, new IllegalStateException("Adapter does not support active connector work"));
                }
            } catch (RuntimeException exception) {
                fail(now, exception);
            }
        }

        private void runPoll(PollingTelemetryAdapter adapter, Instant now) {
            Duration interval = scheduler.pollingInterval(config);
            List<IncomingTelemetryMessage> messages = adapter.poll(config);
            Optional<ConnectorLease> renewed = leaseManager.renew(lease);
            if (renewed.isEmpty()) {
                leaseLost(now);
                return;
            }
            lease = renewed.get();
            for (IncomingTelemetryMessage message : messages) {
                if (!leaseManager.isCurrent(lease)) {
                    leaseLost(now);
                    return;
                }
                pipeline.ingest(message);
            }
            state = ConnectorRuntimeState.RUNNING;
            consecutiveFailures = 0;
            nextAttemptAt = now.plus(interval);
            healthManager.running(config.sourceId(), now);
        }

        private void runSubscription(SubscriptionTelemetryAdapter adapter, Instant now) {
            Duration interval = scheduler.pollingInterval(config);
            if (!subscriptionStarted) {
                adapter.startSubscription(config, this);
                subscriptionStarted = true;
            }
            state = ConnectorRuntimeState.RUNNING;
            consecutiveFailures = 0;
            nextAttemptAt = now.plus(interval);
            healthManager.running(config.sourceId(), now);
        }

        private void fail(Instant now, RuntimeException exception) {
            consecutiveFailures++;
            state = consecutiveFailures > 2 ? ConnectorRuntimeState.FAILED : ConnectorRuntimeState.DEGRADED;
            nextAttemptAt = now.plus(backoffPolicy.nextDelay(consecutiveFailures));
            if (state == ConnectorRuntimeState.FAILED) {
                healthManager.failed(config.sourceId(), consecutiveFailures, now, nextAttemptAt, exception.getMessage());
            } else {
                healthManager.degraded(config.sourceId(), consecutiveFailures, now, nextAttemptAt, exception.getMessage());
            }
        }

        private void leaseLost(Instant now) {
            stopSubscriptionIfStarted();
            releaseLease();
            state = ConnectorRuntimeState.LEASE_LOST;
            nextAttemptAt = now.plus(scheduler.pollingInterval(config));
            healthManager.leaseLost(config.sourceId(), now);
        }

        private void stop() {
            stopSubscriptionIfStarted();
            releaseLease();
            state = ConnectorRuntimeState.STOPPED;
            healthManager.stopped(config.sourceId());
        }

        private void releaseLease() {
            if (lease != null) {
                leaseManager.release(lease);
                lease = null;
            }
        }

        private void stopSubscriptionIfStarted() {
            if (!subscriptionStarted) {
                return;
            }
            adapterResolver.resolve(config)
                    .filter(SubscriptionTelemetryAdapter.class::isInstance)
                    .map(SubscriptionTelemetryAdapter.class::cast)
                    .ifPresent(adapter -> adapter.stopSubscription(config));
            subscriptionStarted = false;
        }

        @Override
        public void accept(IncomingTelemetryMessage message) {
            Optional<ConnectorLease> renewed = lease == null ? Optional.empty() : leaseManager.renew(lease);
            if (renewed.isPresent()) {
                lease = renewed.get();
                pipeline.ingest(message);
            } else {
                leaseLost(clock.instant());
            }
        }

        @Override
        public void reject(SourceRuntimeConfig sourceRuntimeConfig, RuntimeException exception) {
            stopSubscriptionIfStarted();
            fail(clock.instant(), exception);
        }
    }
}
