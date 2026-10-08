package com.voltcanary.dashboard.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalController {
    @GetMapping("/internal/ping")
    public Map<String, String> ping() {
        return Map.of("service", "dashboard-service", "status", "UP");
    }
}
