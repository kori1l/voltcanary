package com.voltcanary.simulator.service;

import com.voltcanary.simulator.config.SimulationTargetProperties;
import com.voltcanary.simulator.repository.SimulationRunRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SimulationUserAccess {
    private final RestClient devices;
    private final SimulationRunRepository runs;

    public SimulationUserAccess(SimulationTargetProperties properties, RestClient.Builder builder,
            SimulationRunRepository runs) {
        this.devices = builder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
        this.runs = runs;
    }

    public void requireDevice(String authorization, UUID device) {
        try {
            devices.get().uri("/devices/{id}", device).header("Authorization", authorization)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation device not found");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device authorization unavailable");
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device authorization unavailable");
        }
    }

    public void requireRun(String authorization, UUID run) {
        var saved = runs.findById(run)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found"));
        requireDevice(authorization, saved.getDeviceId());
    }
}
