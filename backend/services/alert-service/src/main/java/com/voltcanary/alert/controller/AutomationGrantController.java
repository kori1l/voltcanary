package com.voltcanary.alert.controller;

import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class AutomationGrantController {

    private final AutomationGrantReviews grants;

    @PostMapping("/grant-review-reservations")
    public AutomationGrantReviews.View reserve(
        @RequestHeader("X-User-Id") UUID actor,
        @RequestBody byte[] body
    ) {
        return grants.reserve(actor, body);
    }

    @PostMapping("/grant-reviews")
    public AutomationGrantReviews.View prepare(
        @RequestHeader("X-User-Id") UUID actor,
        @RequestHeader("Authorization") String authorization,
        @RequestBody byte[] body
    ) {
        return grants.prepare(actor, authorization, body);
    }

    @GetMapping("/grant-reviews/{id}")
    public ResponseEntity<AutomationGrantReviews.View> inspect(
        @RequestHeader("X-User-Id") UUID actor,
        @PathVariable UUID id
    ) {
        return review(grants.inspect(actor, id));
    }

    @PostMapping("/grant-reviews/{id}/apply")
    public ResponseEntity<AutomationGrantReviews.View> apply(
        @RequestHeader("X-User-Id") UUID actor,
        @RequestHeader("Authorization") String authorization,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        return review(grants.apply(actor, authorization, id, body));
    }

    @PostMapping("/grant-reviews/{id}/reconcile")
    public ResponseEntity<AutomationGrantReviews.View> reconcile(
        @RequestHeader("X-User-Id") UUID actor,
        @PathVariable UUID id,
        @RequestBody(required = false) byte[] body
    ) {
        return review(grants.reconcile(actor, id, body));
    }

    @GetMapping("/grants/{id}")
    public AutomationGrantReviews.GrantView grant(
        @RequestHeader("X-User-Id") UUID actor,
        @RequestHeader("Authorization") String authorization,
        @PathVariable UUID id
    ) {
        return grants.grant(actor, authorization, id);
    }

    @PostMapping("/grants/{id}/revoke")
    public ResponseEntity<AutomationGrantReviews.RevocationView> revoke(
        @RequestHeader("X-User-Id") UUID actor,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var value = grants.revoke(actor, id, body);
        return revokeResponse(value);
    }

    @GetMapping("/grant-revocations/{id}")
    public ResponseEntity<AutomationGrantReviews.RevocationView> revocation(
        @RequestHeader("X-User-Id") UUID actor,
        @PathVariable UUID id
    ) {
        return revokeResponse(grants.revocation(actor, id));
    }

    private static ResponseEntity<AutomationGrantReviews.View> review(
        AutomationGrantReviews.View value
    ) {
        if (
            List.of("APPLYING", "UNKNOWN").contains(value.status())
        ) return ResponseEntity.accepted().header("Retry-After", "2").body(value);
        if (value.status().equals("FAILED")) return ResponseEntity.status(409).body(value);
        return ResponseEntity.ok(value);
    }

    private static ResponseEntity<AutomationGrantReviews.RevocationView> revokeResponse(
        AutomationGrantReviews.RevocationView value
    ) {
        if (List.of("PENDING", "UNKNOWN").contains(value.status())) return ResponseEntity.accepted()
            .header("Retry-After", "2")
            .body(value);
        if (value.status().equals("FAILED")) return ResponseEntity.status(409).body(value);
        return ResponseEntity.ok(value);
    }
}
