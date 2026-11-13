package com.voltcanary.alert.controller;

import com.voltcanary.alert.config.VclWorkerIdentityProperties;
import com.voltcanary.alert.configurable.adapter.RuleWindowAlertEffects;
import com.voltcanary.common.security.VclWorkerAssertion;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RuleWindowAlertEffectsController {
    private final RuleWindowAlertEffects effects;
    private final VclWorkerAssertion verifier;

    public RuleWindowAlertEffectsController(RuleWindowAlertEffects effects, VclWorkerIdentityProperties identity) {
        this.effects = effects;
        verifier = identity.forAudience("alert-service");
    }

    @PostMapping("/internal/vcl/window-alert-proposals/{id}")
    public RuleWindowAlertEffects.Receipt apply(
            @RequestHeader(value = "X-VCL-Worker-Assertion", required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        return effects.apply(scope(assertion, id, body, "WINDOW_ALERT_EFFECT", ""), id, body);
    }

    @PostMapping("/internal/vcl/window-alert-proposals/{id}/receipt")
    public RuleWindowAlertEffects.Receipt receipt(
            @RequestHeader(value = "X-VCL-Worker-Assertion", required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        return effects.reconcile(scope(assertion, id, body, "WINDOW_ALERT_RECEIPT", "/receipt"), id, body);
    }

    @PostMapping("/internal/vcl/window-alert-proposals/{id}/settlement")
    public RuleWindowAlertEffects.Settlement settlement(
            @RequestHeader(value = "X-VCL-Worker-Assertion", required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        return effects.settle(scope(assertion, id, body, "WINDOW_ALERT_SETTLEMENT", "/settlement"), id, body);
    }

    private VclWorkerAssertion.Scope scope(String assertion, UUID id, byte[] body, String operation, String suffix) {
        if (verifier == null || body.length > 8192) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            return verifier.verify(assertion, operation, id,
                    VclWorkerAssertion.requestDigest("POST", "/internal/vcl/window-alert-proposals/" + id + suffix, body));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
