package com.voltcanary.ingestion.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "ingestion-service");
    }
}
