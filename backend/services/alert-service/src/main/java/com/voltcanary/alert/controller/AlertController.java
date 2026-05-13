package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.AlertActionResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.service.AlertService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<AlertResponse> listAlerts() {
        return alertService.listAlerts();
    }

    @GetMapping("/{id}")
    public AlertResponse getAlert(@PathVariable("id") UUID id) {
        return alertService.getAlert(id);
    }

    @PostMapping("/{id}/ack")
    public AlertActionResponse acknowledge(@PathVariable("id") UUID id) {
        AlertResponse alert = alertService.acknowledge(id);
        return new AlertActionResponse(alert.status(), alert.id());
    }

    @PostMapping("/{id}/resolve")
    public AlertActionResponse resolve(@PathVariable("id") UUID id) {
        AlertResponse alert = alertService.resolve(id);
        return new AlertActionResponse(alert.status(), alert.id());
    }
}
