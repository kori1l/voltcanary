package com.voltcanary.device.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

/** Retriable saga. Each participant is idempotent; no transaction spans a network request. */
public class DeviceDeletionCoordinator {
    private final DeviceDeletionFinalization finalization;
    private final JdbcTemplate jdbc;
    private final String token;
    private final List<Participant> participants;
    private record Participant(String name, RestClient client) { }

    public DeviceDeletionCoordinator(JdbcTemplate jdbc, DeviceDeletionFinalization finalization,
            String token,
            String telemetry,
            String analytics,
            String alerts,
            String simulator,
            String hsl) {
        this.jdbc = jdbc;
        this.finalization = finalization;
        this.token = token;
        participants = List.of(participant("simulator", simulator), participant("hsl", hsl),
                participant("telemetry", telemetry), participant("analytics", analytics), participant("alerts", alerts));
    }

    private Participant participant(String name, String url) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new Participant(name, com.voltcanary.common.web.JacksonRestClients.builder().baseUrl(url).requestFactory(factory).build());
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 10000)
    public void resume() {
        for (Map<String, Object> row : jdbc.queryForList("select device_id,completed_steps,source_ids_json from device_deletions where status <> 'COMPLETED' order by updated_at limit 3")) {
            UUID device = (UUID) row.get("device_id");
            List<String> steps = new ArrayList<>(Arrays.stream(String.valueOf(row.get("completed_steps")).split(",")).filter(s -> !s.isBlank()).toList());
            String error = null;
            for (Participant participant : participants) {
                if (steps.contains(participant.name())) continue;
                try {
                    if (!participant.name().equals("hsl") || String.valueOf(row.get("source_ids_json")).contains("hsl-")) {
                        participant.client().post().uri("/internal/device-deletions/{id}", device)
                                .header("X-Internal-Service-Token", token).retrieve().toBodilessEntity();
                    }
                    steps.add(participant.name());
                    jdbc.update("update device_deletions set completed_steps=?,updated_at=CURRENT_TIMESTAMP where device_id=?", String.join(",", steps), device);
                } catch (RuntimeException unavailable) {
                    // Do not persist response bodies: a remote failure can contain private configuration.
                    error = participant.name().equals("analytics") && unavailable instanceof org.springframework.web.client.RestClientResponseException response && response.getStatusCode().value() == 409
                            ? "Cleanup blocked: dependent ML datasets or feature snapshots must be removed first"
                            : "Cleanup pending: " + participant.name();
                }
            }
            if (steps.size() == participants.size()) {
                try { finalization.complete(device); }
                catch (RuntimeException failed) { jdbc.update("update device_deletions set last_error='Cleanup pending: registry' where device_id=?", device); }
                continue;
            }
            jdbc.update("update device_deletions set status=?,last_error=?,updated_at=CURRENT_TIMESTAMP where device_id=?",
                    steps.size() == participants.size() ? "COMPLETED" : "PENDING", error, device);
        }
    }
}
