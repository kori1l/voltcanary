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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConnectorSupervisor {

    private final Map<UUID, ConnectorWorker> workers = new ConcurrentHashMap<>();
    private final Map<UUID, ConnectorWorker> activeWork = new ConcurrentHashMap<>();
    private final ConnectorLeaseManager leaseManager;
    private final IngestionAdapterResolver adapterResolver;
    private final ConnectorScheduler scheduler;
    private final ConnectorBackoffPolicy backoffPolicy;
    private final ConnectorHealthManager healthManager;
    private final TelemetryIngestionPipeline pipeline;
    private final Clock clock;
    private final ConnectorTaskDispatcher tasks;

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
        for (ConnectorWorker worker : workers.values()) {
            worker.dispatch();
        }
    }

    public ConnectorRuntimeState state(UUID dataSourceId) {
        ConnectorWorker worker = workers.get(dataSourceId);
        return worker == null ? ConnectorRuntimeState.STOPPED : worker.state;
    }

    private final class ConnectorWorker implements SubscriptionTelemetrySink {

        private final SourceRuntimeConfig config;
        private volatile ConnectorRuntimeState state = ConnectorRuntimeState.STOPPED;
        private volatile Instant nextAttemptAt = Instant.EPOCH;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile boolean subscriptionStarted;
        private volatile ConnectorLease lease;
        private volatile boolean stopped;
        private final AtomicBoolean heartbeatPending = new AtomicBoolean();
        private final AtomicLong subscriptionGeneration = new AtomicLong();

        private ConnectorWorker(SourceRuntimeConfig config) {
            this.config = config;
            healthManager.starting(config.sourceId());
        }

        private void dispatch() {
            if (stopped || !heartbeatPending.compareAndSet(false, true)) return;
            if (
                !tasks.heartbeat(() -> {
                    try {
                        Instant now = clock.instant();
                        if (
                            stopped ||
                            !heartbeat(now) ||
                            nextAttemptAt.isAfter(now) ||
                            activeWork.putIfAbsent(config.dataSourceId(), this) != null
                        ) return;
                        if (
                            !tasks.work(() -> {
                                try {
                                    if (!stopped) tick(clock.instant());
                                } catch (RuntimeException unavailable) {
                                    leaseLost(clock.instant());
                                } finally {
                                    activeWork.remove(config.dataSourceId(), this);
                                }
                            })
                        ) activeWork.remove(config.dataSourceId(), this);
                    } catch (RuntimeException unavailable) {
                        leaseLost(clock.instant());
                    } finally {
                        heartbeatPending.set(false);
                    }
                })
            ) heartbeatPending.set(false);
        }

        private synchronized boolean heartbeat(Instant now) {
            if (stopped) return false;
            // Ownership must be serviced even when polling/backoff has a longer cadence.
            if (lease != null) {
                var heartbeat = leaseManager.renew(lease);
                if (heartbeat.isEmpty()) {
                    leaseLost(now);
                    return false;
                }
                lease = heartbeat.get();
            }
            return true;
        }

        private void tick(Instant now) {
            if (nextAttemptAt.isAfter(now)) {
                return;
            }
            if (!acquireOwnership(now)) return;
            Optional<TelemetryAdapter> resolved = adapterResolver.resolve(config);
            if (resolved.isEmpty()) {
                fail(
                    now,
                    new IllegalStateException("No adapter registered for " + config.adapterType())
                );
                return;
            }
            TelemetryAdapter adapter = resolved.get();
            try {
                if (adapter instanceof PollingTelemetryAdapter pollingAdapter) {
                    runPoll(pollingAdapter, now);
                } else if (adapter instanceof SubscriptionTelemetryAdapter subscriptionAdapter) {
                    runSubscription(subscriptionAdapter, now);
                } else {
                    fail(
                        now,
                        new IllegalStateException("Adapter does not support active connector work")
                    );
                }
            } catch (RuntimeException exception) {
                stopSubscriptionIfStarted();
                fail(now, exception);
            }
        }

        private synchronized boolean acquireOwnership(Instant now) {
            if (stopped) return false;
            Optional<ConnectorLease> acquired =
                lease == null
                    ? leaseManager.acquire(config.dataSourceId())
                    : leaseManager.renew(lease);
            if (acquired.isEmpty()) {
                leaseLost(now);
                return false;
            }
            lease = acquired.get();
            return true;
        }

        private synchronized boolean renewOwnership(ConnectorLease expected) {
            if (stopped || expected == null || !expected.equals(lease)) return false;
            Optional<ConnectorLease> renewed = leaseManager.renew(expected);
            if (renewed.isEmpty()) {
                leaseLost(clock.instant());
                return false;
            }
            lease = renewed.get();
            return true;
        }

        private synchronized void running(ConnectorLease expected, Instant now, Duration interval) {
            if (stopped || expected == null || !expected.equals(lease)) return;
            state = ConnectorRuntimeState.RUNNING;
            consecutiveFailures.set(0);
            nextAttemptAt = now.plus(interval);
            healthManager.running(config.sourceId(), now);
        }

        private void runPoll(PollingTelemetryAdapter adapter, Instant now) {
            Duration interval = scheduler.pollingInterval(config);
            ConnectorLease ownership = lease;
            List<IncomingTelemetryMessage> messages = adapter.poll(config);
            if (!renewOwnership(ownership)) return;
            for (IncomingTelemetryMessage message : messages) {
                if (
                    stopped ||
                    !java.util.Objects.equals(ownership, lease) ||
                    !leaseManager.isCurrent(ownership)
                ) {
                    leaseLost(now);
                    return;
                }
                pipeline.ingest(message);
            }
            running(ownership, clock.instant(), interval);
        }

        private void runSubscription(SubscriptionTelemetryAdapter adapter, Instant now) {
            Duration interval = scheduler.pollingInterval(config);
            if (!subscriptionStarted) {
                ConnectorLease ownership = lease;
                long generation = subscriptionGeneration.incrementAndGet();
                subscriptionStarted = true;
                adapter.startSubscription(
                    config,
                    new SubscriptionTelemetrySink() {
                        private boolean current() {
                            return (
                                !stopped &&
                                generation == subscriptionGeneration.get() &&
                                java.util.Objects.equals(ownership, lease)
                            );
                        }

                        @Override
                        public void accept(IncomingTelemetryMessage message) {
                            if (current()) ConnectorWorker.this.accept(message);
                        }

                        @Override
                        public void reject(SourceRuntimeConfig source, RuntimeException failure) {
                            if (current()) ConnectorWorker.this.reject(source, failure);
                        }
                    }
                );
                if (
                    stopped ||
                    generation != subscriptionGeneration.get() ||
                    !java.util.Objects.equals(ownership, lease)
                ) {
                    adapter.stopSubscription(config);
                    subscriptionStarted = false;
                    return;
                }
            }
            running(lease, now, interval);
        }

        private synchronized void fail(Instant now, RuntimeException exception) {
            if (stopped || lease == null) return;
            int failures = consecutiveFailures.incrementAndGet();
            state = failures > 2 ? ConnectorRuntimeState.FAILED : ConnectorRuntimeState.DEGRADED;
            nextAttemptAt = now.plus(backoffPolicy.nextDelay(failures));
            if (state == ConnectorRuntimeState.FAILED) {
                healthManager.failed(
                    config.sourceId(),
                    failures,
                    now,
                    nextAttemptAt,
                    exception.getMessage()
                );
            } else {
                healthManager.degraded(
                    config.sourceId(),
                    failures,
                    now,
                    nextAttemptAt,
                    exception.getMessage()
                );
            }
        }

        private synchronized void leaseLost(Instant now) {
            if (stopped) return;
            state = ConnectorRuntimeState.LEASE_LOST;
            nextAttemptAt = now.plus(scheduler.pollingInterval(config));
            healthManager.leaseLost(config.sourceId(), now);
            releaseLease();
            stopSubscriptionIfStarted();
        }

        private synchronized void stop() {
            stopped = true;
            stopSubscriptionIfStarted();
            releaseLease();
            state = ConnectorRuntimeState.STOPPED;
            healthManager.stopped(config.sourceId());
        }

        private synchronized void releaseLease() {
            ConnectorLease released = lease;
            lease = null;
            if (released != null) {
                try {
                    leaseManager.release(released);
                } catch (RuntimeException unavailable) {
                    // Local authority is already dropped; a remote lease expires conservatively.
                }
            }
        }

        private void stopSubscriptionIfStarted() {
            subscriptionGeneration.incrementAndGet();
            if (!subscriptionStarted) {
                return;
            }
            adapterResolver
                .resolve(config)
                .filter(SubscriptionTelemetryAdapter.class::isInstance)
                .map(SubscriptionTelemetryAdapter.class::cast)
                .ifPresent(adapter -> adapter.stopSubscription(config));
            subscriptionStarted = false;
        }

        @Override
        public void accept(IncomingTelemetryMessage message) {
            if (renewOwnership(lease)) {
                pipeline.ingest(message);
            }
        }

        @Override
        public void reject(SourceRuntimeConfig sourceRuntimeConfig, RuntimeException exception) {
            stopSubscriptionIfStarted();
            fail(clock.instant(), exception);
        }
    }
}
