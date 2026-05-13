package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.AlertActionResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    @GetMapping
    public List<Object> listAlerts() {
        return List.of();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getAlert(@PathVariable UUID id) {
        return Map.of("id", id, "message", "alert endpoint is not implemented yet");
    }

    @PostMapping("/{id}/ack")
    public AlertActionResponse acknowledge(@PathVariable UUID id) {
        return new AlertActionResponse("acknowledged", id);
    }

    @PostMapping("/{id}/resolve")
    public AlertActionResponse resolve(@PathVariable UUID id) {
        return new AlertActionResponse("resolved", id);
    }
}
