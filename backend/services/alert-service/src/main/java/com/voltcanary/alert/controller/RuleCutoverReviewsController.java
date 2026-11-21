package com.voltcanary.alert.controller;

import com.voltcanary.alert.configurable.adapter.RuleCutoverReviews;
import com.voltcanary.vcl.VclRuleCutoverIntent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alert-rules/configurable/cutover-reviews")
@RequiredArgsConstructor
public class RuleCutoverReviewsController {
    private final RuleCutoverReviews reviews;

    @PostMapping
    public RuleCutoverReviews.Review prepare(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @RequestBody VclRuleCutoverIntent intent) {
        return reviews.prepare(actor, authorization, intent);
    }

    public record Confirmation(String reviewDigest) { }

    @GetMapping("/{id}/confirmed")
    public RuleCutoverReviews.Review confirmed(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        return reviews.confirmed(actor, authorization, id);
    }

    @PostMapping("/{id}/confirm")
    public RuleCutoverReviews.Review confirm(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader("Authorization") String authorization, @PathVariable UUID id,
            @RequestBody Confirmation confirmation) {
        return reviews.confirm(actor, authorization, id, confirmation.reviewDigest());
    }
}
