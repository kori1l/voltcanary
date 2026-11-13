package com.voltcanary.alert.controller;

import com.voltcanary.alert.configurable.application.ConfigurableRuleReservations;
import com.voltcanary.alert.configurable.domain.RuleReservation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alert-rules/configurable")
public class ConfigurableRuleReservationsController {
    private final ConfigurableRuleReservations reservations;

    public ConfigurableRuleReservationsController(ConfigurableRuleReservations reservations) { this.reservations = reservations; }

    @PostMapping("/review-reservations")
    public RuleReservation reserve(@RequestHeader("X-User-Id") String actor, @RequestBody byte[] body) {
        return reservations.reserve(actor, body);
    }

    @GetMapping("/reviews/{id}")
    public RuleReservation inspect(@RequestHeader("X-User-Id") String actor, @PathVariable UUID id) {
        return reservations.inspect(actor, id);
    }
}
