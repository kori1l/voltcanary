package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.NativeCollectionReviews;
import com.voltcanary.device.configurable.application.NativeReviewRequests;
import com.voltcanary.device.configurable.application.NativeRowWorkflows;
import com.voltcanary.device.configurable.application.NativeReviewRouting;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data")
public class NativeCollectionReviewsController {
    private final NativeCollectionReviews reviews;
    private final AuthenticatedUserResolver users;
    private final NativeRowWorkflows rows;
    private final NativeReviewRouting routing;

    public NativeCollectionReviewsController(NativeCollectionReviews reviews, AuthenticatedUserResolver users, NativeRowWorkflows rows, NativeReviewRouting routing) {
        this.reviews = reviews;
        this.users = users;
        this.rows = rows;
        this.routing = routing;
    }

    @PostMapping("/review-reservations")
    public Object reserve(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        var actor = users.resolve(headers);
        var envelope = new com.voltcanary.vcl.VclParser().readJson(body);
        return envelope.path("kind").asText().equals("MUTATION") ? rows.reserve(actor, body) : reviews.reserve(actor, NativeReviewRequests.reservation(body));
    }

    @PostMapping("/dataset-reviews")
    public NativeCollectionReviews.View prepare(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return reviews.prepare(users.resolve(headers), NativeReviewRequests.preparation(body));
    }

    @GetMapping("/reviews/{id}")
    public Object inspect(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
        var actor = users.resolve(headers);
        return routing.row(actor, id) ? rows.inspect(actor, id) : reviews.inspect(actor, id);
    }

    @PostMapping("/reviews/{id}/apply")
    public ResponseEntity<?> apply(@RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody byte[] body) {
        var actor = users.resolve(headers);
        var request = NativeReviewRequests.application(body);
        return routing.row(actor, id) ? rowOutcome(rows.apply(actor, id, request)) : outcome(reviews.apply(actor, id, request));
    }

    @PostMapping("/reviews/{id}/reconcile")
    public ResponseEntity<?> reconcile(@RequestHeader HttpHeaders headers, @PathVariable UUID id,
            @RequestBody(required = false) byte[] body) {
        var actor = users.resolve(headers);
        NativeReviewRequests.reconciliation(body);
        return routing.row(actor, id) ? rowOutcome(rows.reconcile(actor, id)) : outcome(reviews.reconcile(actor, id));
    }

    private static ResponseEntity<NativeCollectionReviews.View> outcome(NativeCollectionReviews.View result) {
        return result.status().equals("SUCCEEDED") ? ResponseEntity.ok(result) : ResponseEntity.accepted().header("Retry-After", "2").body(result);
    }

    private static ResponseEntity<NativeRowWorkflows.View> rowOutcome(NativeRowWorkflows.View result) {
        if (result.status().equals("FAILED")) return ResponseEntity.status(409).body(result);
        return result.status().equals("SUCCEEDED") ? ResponseEntity.ok(result) : ResponseEntity.accepted().header("Retry-After", "2").body(result);
    }
}
