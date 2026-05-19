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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SimulationRunManager {

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
        if (registry.isRunning(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation is already running");
        }

        SimulationRun run = new SimulationRun();
        run.setId(UUID.randomUUID());
        run.setConfigId(config.getId());
        run.setAccountId(config.getAccountId());
        run.setDeviceId(config.getDeviceId());
        run.setDataSourceId(config.getDataSourceId());
        run.setStatus(SimulationRunStatus.STARTING);
        run.setStartedAt(Instant.now());
        run.setStartedBy(userId);
        run.setGeneratedPoints(0);
        runRepository.save(run);
        event(run.getId(), "STARTING", "Simulation starting");
        meterRegistry.counter("simulator_runs_started_total").increment();

        var future = simulationRunner.schedule(
                run.getId(),
                () -> tick(run.getId(), config.getSourceToken(), model),
                model.interval().toSeconds());
        if (!registry.putIfAbsent(deviceId, future)) {
            future.cancel(false);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation is already running");
        }
        run.setStatus(SimulationRunStatus.RUNNING);
        runRepository.save(run);
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
        runRepository.save(run);
        event(run.getId(), "STOPPING", "Simulation stopping");
        registry.remove(deviceId).ifPresent(future -> future.cancel(false));
        run.setStatus(SimulationRunStatus.STOPPED);
        run.setStoppedAt(Instant.now());
        run.setStopReason(reason == null ? "Stopped by user" : reason);
        runRepository.save(run);
        event(run.getId(), "STOPPED", run.getStopReason());
        meterRegistry.counter("simulator_runs_stopped_total").increment();
        return toResponse(run, config);
    }

    @Transactional(readOnly = true)
    public SimulationRunResponse status(UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        return runRepository.findByDeviceIdOrderByStartedAtDesc(deviceId).stream()
                .findFirst()
                .map(run -> toResponse(run, config))
                .orElse(new SimulationRunResponse(null, SimulationRunStatus.STOPPED, config.getProfile().name(), config.getProtocol(), 0, null, null, null));
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

    private void tick(UUID runId, String sourceToken, SimulationConfigModel model) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SimulationRun run = runRepository.findById(runId).orElseThrow();
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
                runRepository.save(run);
                meterRegistry.counter("simulator_points_generated_total", "protocol", model.protocol()).increment();
            });
        } catch (Exception exception) {
            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(SimulationRunStatus.FAILED);
                run.setLastError(exception.getMessage());
                run.setStoppedAt(Instant.now());
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
}
