package com.voltcanary.simulator;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.domain.SimulationProtocol;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import com.voltcanary.simulator.dto.SaveSimulationConfigRequest;
import com.voltcanary.simulator.repository.SimulationRunEventRepository;
import com.voltcanary.simulator.runner.SimulationRunManager;
import com.voltcanary.simulator.runner.SimulationRunner;
import com.voltcanary.simulator.service.SimulationConfigService;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
class SimulationPersistenceTest {

    @Autowired
    private SimulationConfigService configService;

    @Autowired
    private SimulationRunManager runManager;

    @Autowired
    private SimulationRunEventRepository runEventRepository;

    @MockitoBean
    private SimulationRunner simulationRunner;

    private ScheduledFuture<?> scheduledFuture;

    @BeforeEach
    void setUp() {
        scheduledFuture = mock(ScheduledFuture.class);
        doReturn(scheduledFuture).when(simulationRunner).schedule(any(), any(), anyLong());
    }

    @Test
    void savesConfigVersionsAndRunHistory() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        configService.save(userId, deviceId, request(dataSourceId, "sim-fridge-001", "v1"));
        configService.save(userId, deviceId, request(dataSourceId, "sim-fridge-001", "v2"));

        assertThat(configService.versions(deviceId))
                .extracting("version")
                .containsExactly(2, 1);

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

    private SaveSimulationConfigRequest request(UUID dataSourceId, String sourceId, String name) {
        return new SaveSimulationConfigRequest(
                dataSourceId,
                name,
                SimulationProfile.FRIDGE,
                SimulationProtocol.HTTP,
                yaml(sourceId, name),
                "vc_src_live_test_token");
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
