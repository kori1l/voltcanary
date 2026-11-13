package com.voltcanary.alert.controller;

import com.voltcanary.alert.configurable.adapter.RulePauseReviews;
import com.voltcanary.vcl.VclRulePauseIntent;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alert-rules/configurable/pause-reviews")
public class RulePauseReviewsController {
    private final RulePauseReviews reviews;

    public RulePauseReviewsController(RulePauseReviews reviews) {
        this.reviews = reviews;
    }

    @PostMapping
    public RulePauseReviews.Review prepare(@RequestHeader("X-User-Id") UUID actor, @RequestBody VclRulePauseIntent intent) {
        return reviews.prepare(actor, intent);
    }

    @PostMapping("/{id}/confirm")
    public RulePauseReviews.Review confirm(@RequestHeader("X-User-Id") UUID actor, @PathVariable UUID id,
            @RequestBody RuleCutoverReviewsController.Confirmation confirmation) {
        return reviews.confirm(actor, id, confirmation.reviewDigest());
    }

    @GetMapping("/{id}/confirmed")
    public RulePauseReviews.Review confirmed(@RequestHeader("X-User-Id") UUID actor, @PathVariable UUID id) {
        return reviews.confirmed(actor, id);
    }
}
