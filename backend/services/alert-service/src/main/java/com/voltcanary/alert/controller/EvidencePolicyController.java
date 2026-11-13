package com.voltcanary.alert.controller;

import com.voltcanary.alert.configurable.application.EvidencePolicyReviews;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alert-rules/configurable")
public class EvidencePolicyController {
    private final EvidencePolicyReviews policies;
    public EvidencePolicyController(EvidencePolicyReviews policies) { this.policies = policies; }

    @PostMapping("/evidence-policy-reservations")
    public EvidencePolicyReviews.View reserve(@RequestHeader("X-User-Id") UUID actor, @RequestHeader("Authorization") String authorization,
            @RequestBody byte[] body) { return policies.reserve(actor, authorization, body); }

    @PostMapping("/evidence-policy-reviews")
    public EvidencePolicyReviews.View prepare(@RequestHeader("X-User-Id") UUID actor, @RequestHeader("Authorization") String authorization,
            @RequestBody byte[] body) { return policies.prepare(actor, authorization, body); }

    @GetMapping("/evidence-policy-reviews/{id}")
    public ResponseEntity<EvidencePolicyReviews.View> inspect(@RequestHeader("X-User-Id") UUID actor, @PathVariable UUID id) {
        return response(policies.inspect(actor, id));
    }

    @PostMapping("/evidence-policy-reviews/{id}/apply")
    public ResponseEntity<EvidencePolicyReviews.View> apply(@RequestHeader("X-User-Id") UUID actor, @RequestHeader("Authorization") String authorization,
            @PathVariable UUID id, @RequestBody byte[] body) { return response(policies.apply(actor, authorization, id, body)); }

    @PostMapping("/evidence-policy-reviews/{id}/reconcile")
    public ResponseEntity<EvidencePolicyReviews.View> reconcile(@RequestHeader("X-User-Id") UUID actor, @PathVariable UUID id,
            @RequestBody(required = false) byte[] body) { return response(policies.reconcile(actor, id, body)); }

    @GetMapping("/evidence-policies/{id}")
    public EvidencePolicyReviews.PolicyView policy(@RequestHeader("X-User-Id") UUID actor, @RequestHeader("Authorization") String authorization,
            @PathVariable UUID id) { return policies.policy(actor, authorization, id); }

    private static ResponseEntity<EvidencePolicyReviews.View> response(EvidencePolicyReviews.View value) {
        if (List.of("APPLYING", "UNKNOWN").contains(value.status())) return ResponseEntity.accepted().header("Retry-After", "2").body(value);
        return ResponseEntity.status(value.status().equals("FAILED") ? 409 : 200).body(value);
    }
}
