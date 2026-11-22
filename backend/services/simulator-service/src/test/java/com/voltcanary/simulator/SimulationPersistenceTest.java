package com.voltcanary.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.domain.SimulationProtocol;
import com.voltcanary.simulator.domain.SimulationRun;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import com.voltcanary.simulator.dto.SaveSimulationConfigRequest;
import com.voltcanary.simulator.repository.SimulationConfigRepository;
import com.voltcanary.simulator.repository.SimulationRunEventRepository;
import com.voltcanary.simulator.repository.SimulationRunRepository;
import com.voltcanary.simulator.runner.SimulationRunManager;
import com.voltcanary.simulator.runner.SimulationRunner;
import com.voltcanary.simulator.service.SimulationProfileService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class SimulationPersistenceTest {

    @Autowired
    private SimulationProfileService profileService;

    @Autowired
    private SimulationRunManager runManager;

    @Autowired
    private SimulationRunEventRepository runEventRepository;

    @Autowired
    private SimulationRunRepository runRepository;

    @Autowired
    private SimulationConfigRepository configRepository;

    @MockitoBean
    private SimulationRunner simulationRunner;

    private ScheduledFuture<?> scheduledFuture;

    @BeforeEach
    void setUp() {
        scheduledFuture = mock(ScheduledFuture.class);
        doReturn(scheduledFuture).when(simulationRunner).schedule(any(), any(), anyLong());
    }

    @Test
    void runHistoryPagesAreBoundedStableAndDeviceScoped() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        profileService.save(userId, deviceId, request(source, "sim-history-pages", "pages"));
        var config = configRepository.findByDeviceId(deviceId).orElseThrow();
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 7; i++) {
            var run = activeRun(config.getId(), config.getAccountId(), deviceId, source);
            run.setId(new UUID(0, i));
            run.setStartedAt(timestamp);
            run.setStatus(SimulationRunStatus.STOPPED);
            run.setActiveKey(null);
            runRepository.saveAndFlush(run);
        }
        var foreign = activeRun(config.getId(), config.getAccountId(), UUID.randomUUID(), source);
        runRepository.saveAndFlush(foreign);
        var first = runManager.runs(deviceId, 0, 3);
        var second = runManager.runs(deviceId, 1, 3);
        var third = runManager.runs(deviceId, 2, 3);
        assertThat(first.totalElements()).isEqualTo(7);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.content())
            .extracting("runId")
            .containsExactly(new UUID(0, 7), new UUID(0, 6), new UUID(0, 5));
        assertThat(second.content())
            .extracting("runId")
            .containsExactly(new UUID(0, 4), new UUID(0, 3), new UUID(0, 2));
        assertThat(third.content()).extracting("runId").containsExactly(new UUID(0, 1));
        assertThat(runManager.runs(deviceId, 3, 3).content()).isEmpty();
        assertThatThrownBy(() -> runManager.runs(deviceId, -1, 25)).isInstanceOf(
            ResponseStatusException.class
        );
        assertThatThrownBy(() -> runManager.runs(deviceId, 0, 101)).isInstanceOf(
            ResponseStatusException.class
        );
    }

    @Test
    void aSavedCredentialIsRetainedOnlyForTheSameSource() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID firstSource = UUID.randomUUID();
        UUID secondSource = UUID.randomUUID();
        profileService.save(userId, deviceId, request(firstSource, "sim-first", "original"));

        var sameSource = profileService.save(
            userId,
            deviceId,
            new SaveSimulationConfigRequest(
                firstSource,
                "rename",
                SimulationProfile.FRIDGE,
                SimulationProtocol.HTTP,
                yaml("sim-first", "rename"),
                null
            )
        );
        assertThat(sameSource.hasSourceToken()).isTrue();

        var changedSource = profileService.save(
            userId,
            deviceId,
            new SaveSimulationConfigRequest(
                secondSource,
                "rebind",
                SimulationProfile.FRIDGE,
                SimulationProtocol.HTTP,
                yaml("sim-second", "rebind"),
                null
            )
        );
        assertThat(changedSource.hasSourceToken()).isFalse();
        assertThat(
            configRepository.findByDeviceId(deviceId).orElseThrow().getSourceToken()
        ).isNull();
        assertThatThrownBy(() -> runManager.start(userId, deviceId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Simulation source token is missing");
    }

    @Test
    void savesConfigVersionsAndRunHistory() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        profileService.save(userId, deviceId, request(dataSourceId, "sim-fridge-001", "v1"));
        profileService.save(userId, deviceId, request(dataSourceId, "sim-fridge-001", "v2"));

        assertThat(profileService.versions(deviceId)).extracting("version").containsExactly(2, 1);

        var started = runManager.start(userId, deviceId);
        assertThat(started.status()).isEqualTo(SimulationRunStatus.RUNNING);

        var stopped = runManager.stop(deviceId, "test complete");
        assertThat(stopped.status()).isEqualTo(SimulationRunStatus.STOPPED);
        verify(scheduledFuture).cancel(false);

        var detail = runManager.run(started.runId());
        assertThat(detail.events())
            .extracting("eventType")
            .containsExactly("STARTING", "RUNNING", "STOPPING", "STOPPED");
        assertThat(runEventRepository.findByRunIdOrderByCreatedAtAsc(started.runId())).hasSize(4);
    }

    @Test
    void rejectsStartWhenAnotherReplicaOwnsActiveLease() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        profileService.save(
            userId,
            deviceId,
            request(dataSourceId, "sim-fridge-active-lease", "active-lease")
        );
        var config = configRepository.findByDeviceId(deviceId).orElseThrow();
        SimulationRun run = activeRun(
            config.getId(),
            config.getAccountId(),
            deviceId,
            dataSourceId
        );
        run.setLeaseUntil(Instant.now().plusSeconds(300));
        runRepository.saveAndFlush(run);

        assertThatThrownBy(() -> runManager.start(userId, deviceId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void startsAfterExpiredReplicaLeaseIsReconciled() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        profileService.save(
            userId,
            deviceId,
            request(dataSourceId, "sim-fridge-expired-lease", "expired-lease")
        );
        var config = configRepository.findByDeviceId(deviceId).orElseThrow();
        SimulationRun run = activeRun(
            config.getId(),
            config.getAccountId(),
            deviceId,
            dataSourceId
        );
        run.setLeaseUntil(Instant.now().minusSeconds(1));
        runRepository.saveAndFlush(run);

        var started = runManager.start(userId, deviceId);

        assertThat(started.status()).isEqualTo(SimulationRunStatus.RUNNING);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(
            SimulationRunStatus.STOPPED
        );
    }

    @Test
    void extendsActiveRunLeaseWithNamedParameters() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant leaseUntil = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS);

        profileService.save(
            userId,
            deviceId,
            request(dataSourceId, "sim-fridge-lease-extension", "lease-extension")
        );
        var config = configRepository.findByDeviceId(deviceId).orElseThrow();
        SimulationRun run = activeRun(
            config.getId(),
            config.getAccountId(),
            deviceId,
            dataSourceId
        );
        run.setId(runId);
        run.setLeaseOwner("simulator-test-replica");
        runRepository.saveAndFlush(run);

        int updated = runRepository.extendLease(runId, "simulator-test-replica", leaseUntil);

        assertThat(updated).isEqualTo(1);
        assertThat(runRepository.findById(runId).orElseThrow().getLeaseUntil()).isEqualTo(
            leaseUntil
        );
    }

    private SimulationRun activeRun(
        UUID configId,
        UUID accountId,
        UUID deviceId,
        UUID dataSourceId
    ) {
        SimulationRun run = new SimulationRun();
        run.setId(UUID.randomUUID());
        run.setConfigId(configId);
        run.setAccountId(accountId);
        run.setDeviceId(deviceId);
        run.setDataSourceId(dataSourceId);
        run.setStatus(SimulationRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setGeneratedPoints(0);
        run.setActiveKey("ACTIVE");
        run.setLeaseOwner("other-replica");
        return run;
    }

    private SaveSimulationConfigRequest request(UUID dataSourceId, String sourceId, String name) {
        return new SaveSimulationConfigRequest(
            dataSourceId,
            name,
            SimulationProfile.FRIDGE,
            SimulationProtocol.HTTP,
            yaml(sourceId, name),
            "vc_src_live_test_token"
        );
    }

    private String yaml(String sourceId, String name) {
        return """
        schemaVersion: 1
        simulator:
          name: %s
          profile: FRIDGE
          protocol: HTTP
        target:
          sourceId: %s
        schedule:
          interval: 1h
          duration: 1h
        metrics:
          activePowerW:
            type: constant
            value: 120.0
          voltageV:
            type: constant
            value: 230.0
        """.formatted(name, sourceId);
    }
}
