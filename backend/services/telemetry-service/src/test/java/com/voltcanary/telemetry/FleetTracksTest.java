package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.voltcanary.telemetry.repository.PositionRepository;
import com.voltcanary.telemetry.service.PositionReads;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetTracksTest {

    @Test
    void capsFleetHistoryToFifteenMinutesAndEvidenceTime() {
        var repository = mock(PositionRepository.class);
        var reads = new PositionReads(repository);
        var device = UUID.randomUUID();
        var end = Instant.parse("2026-10-11T19:00:00Z");
        var result = reads.fleetEvidence(
            Map.of(device, "gps"),
            end.minusSeconds(21600),
            end,
            end.plusSeconds(10)
        );
        assertThat(result.tracks()).hasSize(1);
        assertThat(result.tracks().getFirst().from()).isEqualTo(end.minusSeconds(900));
        verify(repository).history(device, "gps", end.minusSeconds(900), end);
        reads.fleetEvidence(Map.of(device, "gps"), end.minusSeconds(60), end, end);
        verify(repository).history(device, "gps", end.minusSeconds(60), end);
    }
}
