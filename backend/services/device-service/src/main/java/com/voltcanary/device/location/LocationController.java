package com.voltcanary.device.location;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/locations")
public class LocationController {

    @PostMapping
    public Map<String, String> create(@RequestBody Map<String, Object> request) {
        return Map.of("message", "create location endpoint is not implemented yet");
    }

    @GetMapping
    public List<Map<String, String>> list() {
        return List.of();
    }

    @GetMapping("/{id}")
    public Map<String, String> get(@PathVariable UUID id) {
        return Map.of("message", "get location endpoint is not implemented yet");
    }

    @PatchMapping("/{id}")
    public Map<String, String> update(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        return Map.of("message", "update location endpoint is not implemented yet");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable UUID id) {
        return Map.of("message", "delete location endpoint is not implemented yet");
    }
}
