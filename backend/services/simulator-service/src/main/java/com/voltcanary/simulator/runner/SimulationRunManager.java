package com.voltcanary.simulator.runner;

import com.voltcanary.simulator.domain.SimulationConfig;
import com.voltcanary.simulator.domain.SimulationConfigStatus;
import com.voltcanary.simulator.domain.SimulationRun;
import com.voltcanary.simulator.domain.SimulationRunEvent;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import com.voltcanary.simulator.dto.SimulationRunResponse;
import com.voltcanary.simulator.dto.SimulationRunDetailResponse;
import com.voltcanary.simulator.dto.SimulationRunEventResponse;
import com.voltcanary.simulator.generator.GenericApplianceGenerator;
import com.voltcanary.simulator.generator.SimulationContext;
import com.voltcanary.simulator.repository.SimulationConfigRepository;
import com.voltcanary.simulator.repository.SimulationRunEventRepository;
import com.voltcanary.simulator.repository.SimulationRunRepository;
import com.voltcanary.simulator.sender.TelemetrySenderRegistry;
import com.voltcanary.simulator.yaml.SimulationConfigModel;
import com.voltcanary.simulator.yaml.SimulationYamlParser;
import com.voltcanary.simulator.yaml.SimulationYamlValidator;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SimulationRunManager {

    private static final String ACTIVE_KEY = "ACTIVE";
    private static final Duration MIN_LEASE = Duration.ofMinutes(5);
    private static final Duration LEASE_GRACE = Duration.ofSeconds(30);

    private final SimulationConfigRepository configRepository;
    private final SimulationRunRepository runRepository;
    private final SimulationRunEventRepository eventRepository;
    private final SimulationYamlParser parser;
    private final SimulationYamlValidator validator;
    private final GenericApplianceGenerator generator;
    private final TelemetrySenderRegistry senderRegistry;
    private final RunningSimulationRegistry registry;
    private final MeterRegistry meterRegistry;
    private final SimulationRunner simulationRunner;
    private final String leaseOwner;

    public SimulationRunManager(
            SimulationConfigRepository configRepository,
            SimulationRunRepository runRepository,
            SimulationRunEventRepository eventRepository,
            SimulationYamlParser parser,
            SimulationYamlValidator validator,
            GenericApplianceGenerator generator,
            TelemetrySenderRegistry senderRegistry,
            RunningSimulationRegistry registry,
            SimulationRunner simulationRunner,
            MeterRegistry meterRegistry) {
        this.configRepository = configRepository;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.parser = parser;
        this.validator = validator;
        this.generator = generator;
        this.senderRegistry = senderRegistry;
        this.registry = registry;
        this.simulationRunner = simulationRunner;
        this.meterRegistry = meterRegistry;
        this.leaseOwner = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
        meterRegistry.gauge("simulator_active_runs", registry, RunningSimulationRegistry::activeCount);
    }

    @Transactional
    public SimulationRunResponse start(UUID userId, UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        if (config.getStatus() == SimulationConfigStatus.INVALID || !validator.validate(config.getYamlConfig()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Simulation config is invalid");
        }
        SimulationConfigModel model = parser.parse(config.getYamlConfig());
        if ("HTTP".equalsIgnoreCase(model.protocol()) && (config.getSourceToken() == null || config.getSourceToken().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Simulation source token is missing");
        }
        stopExpiredActiveRuns(Instant.now());
        if (runRepository.findFirstByDeviceIdAndActiveKeyOrderByStartedAtDesc(deviceId, ACTIVE_KEY).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation is already running");
        }

        long intervalSeconds = model.interval().toSeconds();
        Instant now = Instant.now();
        SimulationRun run = new SimulationRun();
        run.setId(UUID.randomUUID());
        run.setConfigId(config.getId());
        run.setAccountId(config.getAccountId());
        run.setDeviceId(config.getDeviceId());
        run.setDataSourceId(config.getDataSourceId());
        run.setStatus(SimulationRunStatus.STARTING);
        run.setStartedAt(now);
        run.setStartedBy(userId);
        run.setGeneratedPoints(0);
        run.setActiveKey(ACTIVE_KEY);
        run.setLeaseOwner(leaseOwner);
        run.setLeaseUntil(now.plus(leaseDuration(intervalSeconds)));
        try {
            runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation is already running", exception);
        }
        event(run.getId(), "STARTING", "Simulation starting");
        meterRegistry.counter("simulator_runs_started_total").increment();

        var future = simulationRunner.schedule(
                run.getId(),
                () -> tick(run.getId(), config.getSourceToken(), model, intervalSeconds),
                intervalSeconds);
        if (!registry.putIfAbsent(deviceId, future)) {
            future.cancel(false);
            stopRun(run, "Stopped because this simulator replica already owns an in-memory run for the device.");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation is already running");
        }
        run.setStatus(SimulationRunStatus.RUNNING);
        runRepository.saveAndFlush(run);
        event(run.getId(), "RUNNING", "Simulation running");
        return toResponse(run, config);
    }

    @Transactional
    public SimulationRunResponse stop(UUID deviceId, String reason) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        SimulationRun run = runRepository.findFirstByDeviceIdAndStatusOrderByStartedAtDesc(deviceId, SimulationRunStatus.RUNNING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Running simulation not found"));
        run.setStatus(SimulationRunStatus.STOPPING);
        run.setStopReason(reason == null ? "Stopped by user" : reason);
        runRepository.save(run);
        event(run.getId(), "STOPPING", "Simulation stopping");
        if (registry.remove(deviceId).map(future -> future.cancel(false)).isPresent()) {
            stopRun(run, run.getStopReason());
            meterRegistry.counter("simulator_runs_stopped_total").increment();
        }
        return toResponse(run, config);
    }

    @Transactional(readOnly = true)
    public SimulationRunResponse status(UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        return runRepository.findByDeviceIdOrderByStartedAtDesc(deviceId).stream()
                .findFirst()
                .map(run -> registry.isRunning(deviceId) || run.getStatus() != SimulationRunStatus.RUNNING
                        ? toResponse(run, config)
                        : staleStoppedResponse(run, config))
                .orElse(new SimulationRunResponse(null, SimulationRunStatus.STOPPED, config.getProfile().name(), config.getProtocol(), 0, null, null, null));
    }

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterruptedRuns() {
        stopExpiredActiveRuns(Instant.now());
    }

    @Transactional
    @Scheduled(fixedDelayString = "${app.simulator.run-reconciliation-interval-ms:60000}")
    public void reconcileExpiredLeases() {
        stopExpiredActiveRuns(Instant.now());
    }

    private void stopExpiredActiveRuns(Instant now) {
        runRepository.findByStatusIn(List.of(
                        SimulationRunStatus.STARTING,
                        SimulationRunStatus.RUNNING,
                        SimulationRunStatus.STOPPING))
                .forEach(run -> {
                    if (registry.isRunning(run.getDeviceId())) {
                        return;
                    }
                    if (run.getLeaseUntil() != null && run.getLeaseUntil().isAfter(now)) {
                        return;
                    }
                    stopRun(run, "Stopped because the simulator run lease expired before the run was resumed.");
                });
    }

    @Transactional(readOnly = true)
    public List<SimulationRunResponse> runs(UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        return runRepository.findByDeviceIdOrderByStartedAtDesc(deviceId).stream()
                .map(run -> toResponse(run, config))
                .toList();
    }

    @Transactional(readOnly = true)
    public SimulationRunDetailResponse run(UUID runId) {
        SimulationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found"));
        SimulationConfig config = configRepository.findById(run.getConfigId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        List<SimulationRunEventResponse> events = eventRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(event -> new SimulationRunEventResponse(
                        event.getId(),
                        event.getEventType(),
                        event.getMessage(),
                        event.getCreatedAt()))
                .toList();
        return new SimulationRunDetailResponse(toResponse(run, config), events);
    }

    private void tick(UUID runId, String sourceToken, SimulationConfigModel model, long intervalSeconds) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SimulationRun run = runRepository.findById(runId).orElseThrow();
            if (!leaseOwner.equals(run.getLeaseOwner())) {
                registry.remove(run.getDeviceId()).ifPresent(future -> future.cancel(false));
                return;
            }
            if (run.getStatus() == SimulationRunStatus.STOPPING) {
                registry.remove(run.getDeviceId()).ifPresent(future -> future.cancel(false));
                stopRun(run, run.getStopReason() == null ? "Stopped by user" : run.getStopReason());
                meterRegistry.counter("simulator_runs_stopped_total").increment();
                return;
            }
            if (run.getStatus() != SimulationRunStatus.RUNNING) {
                registry.remove(run.getDeviceId()).ifPresent(future -> future.cancel(false));
                return;
            }
            runRepository.extendLease(runId, leaseOwner, Instant.now().plus(leaseDuration(intervalSeconds)));
            long tick = run.getGeneratedPoints() + 1;
            var context = new SimulationContext(
                    runId,
                    run.getDeviceId(),
                    run.getDataSourceId(),
                    model.sourceId(),
                    run.getStartedAt(),
                    Instant.now(),
                    tick,
                    model);
            generator.next(context).ifPresent(point -> {
                senderRegistry.senderFor(model.protocol()).send(point, sourceToken);
                run.setGeneratedPoints(tick);
                run.setLeaseUntil(Instant.now().plus(leaseDuration(intervalSeconds)));
                runRepository.save(run);
                meterRegistry.counter("simulator_points_generated_total", "protocol", model.protocol()).increment();
            });
        } catch (Exception exception) {
            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(SimulationRunStatus.FAILED);
                run.setLastError(exception.getMessage());
                run.setStoppedAt(Instant.now());
                run.setActiveKey(null);
                run.setLeaseOwner(null);
                run.setLeaseUntil(null);
                runRepository.save(run);
                registry.remove(run.getDeviceId()).ifPresent(future -> future.cancel(false));
                event(run.getId(), "FAILED", exception.getMessage());
                meterRegistry.counter("simulator_runs_failed_total").increment();
                if ("MQTT".equalsIgnoreCase(model.protocol())) {
                    meterRegistry.counter("simulator_mqtt_publish_errors_total").increment();
                } else {
                    meterRegistry.counter("simulator_http_send_errors_total").increment();
                }
            });
        } finally {
            sample.stop(meterRegistry.timer("simulator_tick_duration_seconds", "protocol", model.protocol()));
        }
    }

    private void event(UUID runId, String type, String message) {
        SimulationRunEvent event = new SimulationRunEvent();
        event.setId(UUID.randomUUID());
        event.setRunId(runId);
        event.setEventType(type);
        event.setMessage(message);
        event.setCreatedAt(Instant.now());
        eventRepository.save(event);
    }

    private void stopRun(SimulationRun run, String reason) {
        run.setStatus(SimulationRunStatus.STOPPED);
        run.setStoppedAt(Instant.now());
        run.setStopReason(reason);
        run.setActiveKey(null);
        run.setLeaseOwner(null);
        run.setLeaseUntil(null);
        runRepository.saveAndFlush(run);
        event(run.getId(), "STOPPED", run.getStopReason());
    }

    private Duration leaseDuration(long intervalSeconds) {
        Duration intervalLease = Duration.ofSeconds(Math.max(1, intervalSeconds)).multipliedBy(2).plus(LEASE_GRACE);
        return intervalLease.compareTo(MIN_LEASE) > 0 ? intervalLease : MIN_LEASE;
    }

    private SimulationRunResponse toResponse(SimulationRun run, SimulationConfig config) {
        return new SimulationRunResponse(
                run.getId(),
                run.getStatus(),
                config.getProfile().name(),
                config.getProtocol(),
                run.getGeneratedPoints(),
                run.getStartedAt(),
                run.getStoppedAt(),
                run.getLastError());
    }

    private SimulationRunResponse staleStoppedResponse(SimulationRun run, SimulationConfig config) {
        return new SimulationRunResponse(
                run.getId(),
                SimulationRunStatus.STOPPED,
                config.getProfile().name(),
                config.getProtocol(),
                run.getGeneratedPoints(),
                run.getStartedAt(),
                run.getStoppedAt() == null ? Instant.now() : run.getStoppedAt(),
                "Stopped because simulator-service is not running this job in memory.");
    }
}
