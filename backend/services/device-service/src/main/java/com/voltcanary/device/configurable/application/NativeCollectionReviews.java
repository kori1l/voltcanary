package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.adapter.NativeDatasetMetadata;
import com.voltcanary.device.configurable.domain.NativeCollectionReview;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.device.configurable.port.NativeCollectionReviewStore;
import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Reviewed native schema lifecycle. Possible dispatch is durably recorded before provisioning. */
@Service
public class NativeCollectionReviews {
    public record View(UUID reviewId, long revision, String status, Instant expiresAt, UUID resourceId,
            Map<String, UUID> fieldIdsByKey, String semanticDigest, String reviewDigest, JsonNode typedDiff, UUID receiptId, String outcomeCode) { }

    private final AccountService accounts;
    private final NativeCollectionReviewStore reviews;
    private final NativeDatasetCatalog catalog;
    private final DataSource source;
    private final Clock clock;

    public NativeCollectionReviews(AccountService accounts, NativeCollectionReviewStore reviews, NativeDatasetCatalog catalog, DataSource source, Clock clock) {
        this.accounts = accounts;
        this.reviews = reviews;
        this.catalog = catalog;
        this.source = source;
        this.clock = clock;
    }

    public View reserve(AuthenticatedUser actor, NativeReviewRequests.Reservation request) {
        return view(reviews.reserve(account(actor), actor.id(), request.fieldKeys(), request.idempotencyKey()));
    }

    public View prepare(AuthenticatedUser actor, NativeReviewRequests.Preparation request) {
        UUID account = account(actor);
        var schema = NativeSchema.compile(request.document(), new NativeDatasetMetadata(source, catalog, account, actor.id()));
        for (var field : schema.fields()) {
            if (field.at("/target/kind").asText().equals("DATASET")) {
                UUID target = UUID.fromString(field.at("/target/datasetId").asText());
                if (!target.equals(schema.id()) && catalog.ready(account, target, 1).isEmpty()) throw new VclException("REFERENCE_TARGET_UNAVAILABLE", "");
            }
        }
        return view(reviews.prepare(account, actor.id(), request.reservationId(), schema));
    }

    public View inspect(AuthenticatedUser actor, UUID id) { return view(reviews.inspect(account(actor), actor.id(), id)); }

    public View apply(AuthenticatedUser actor, UUID id, NativeReviewRequests.Application request) {
        UUID account = account(actor);
        var review = reviews.begin(account, actor.id(), id, request.reviewRevision(), request.reviewDigest(), request.idempotencyKey());
        return dispatch(account, actor.id(), review);
    }

    public View reconcile(AuthenticatedUser actor, UUID id) {
        UUID account = account(actor);
        var review = reviews.inspect(account, actor.id(), id);
        if (!java.util.List.of("APPLYING", "UNKNOWN", "SUCCEEDED").contains(review.status())) throw new VclException("REVIEW_STATE_CONFLICT", "");
        return dispatch(account, actor.id(), review);
    }

    private View dispatch(UUID account, UUID actor, NativeCollectionReview review) {
        if (review.status().equals("SUCCEEDED")) return view(review);
        try {
            var existing = catalog.ready(account, review.resourceId(), 1);
            if (existing.isPresent()) return view(reviews.complete(account, actor, review.id(), existing.get()));
            // Reconciliation may inspect a committed outcome after expiry, but cannot authorize fresh DDL.
            if (!clock.instant().isBefore(review.expiresAt())) return view(reviews.unknown(account, actor, review.id(), "REVIEW_EXPIRED"));
            var metadata = new NativeDatasetMetadata(source, catalog, account, actor);
            if (!metadata.accessScopeDigest().equals(review.scopeDigest())) return view(reviews.unknown(account, actor, review.id(), "ACCESS_SCOPE_CHANGED"));
            var schema = NativeSchema.compile(review.canonicalDocument(), metadata);
            catalog.register(account, actor, review.generation(), review.recordIdFieldId(), schema);
            var result = catalog.provision(account, review.resourceId());
            return view(reviews.complete(account, actor, review.id(), result));
        } catch (VclException failure) {
            if (failure.code().equals("ACCOUNT_ACCESS_REVOKED")) throw failure;
            return view(reviews.unknown(account, actor, review.id(), failure.code()));
        }
    }

    private UUID account(AuthenticatedUser actor) {
        var account = accounts.findByUserId(actor.id()).orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        return account.getId();
    }

    private static View view(NativeCollectionReview review) {
        JsonNode diff = review.canonicalDocument() == null ? null : new com.voltcanary.vcl.VclParser().parse(review.canonicalDocument()).spec();
        return new View(review.id(), review.revision(), review.status(), review.expiresAt(), review.resourceId(), review.fieldIdsByKey(),
                review.semanticDigest(), review.reviewDigest(), diff, review.receiptId(), review.outcomeCode());
    }
}
