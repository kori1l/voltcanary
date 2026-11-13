package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.port.NativeRowMutations;
import com.voltcanary.device.configurable.port.NativeRowReviews;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NativeRowWorkflows {
    public record View(UUID reviewId, long revision, String status, Instant expiresAt, UUID resourceId,
            UUID mutationId, UUID createdRecordId, UUID receiptId, String semanticDigest, String reviewDigest,
            JsonNode typedDiff, String outcomeCode, JsonNode result) { }
    private static final VclParser PARSER = new VclParser();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AccountService accounts;
    private final NativeRowReviews reviews;
    private final NativeRowMutations mutations;

    public NativeRowWorkflows(AccountService accounts, NativeRowReviews reviews, NativeRowMutations mutations) {
        this.accounts = accounts;
        this.reviews = reviews;
        this.mutations = mutations;
    }

    public View reserve(AuthenticatedUser actor, byte[] body) {
        var json = NativeReviewRequests.closed(body, Set.of("kind", "target", "idempotencyKey"));
        if (!json.path("kind").asText().equals("MUTATION")) throw new VclException("RESERVATION_KIND_UNSUPPORTED", "");
        var target = json.path("target");
        if (!target.isObject() || target.size() != 2 || !target.path("datasetId").isTextual()
                || !target.path("datasetId").asText().matches("[0-9a-fA-F-]{36}") || !target.path("schemaRevision").isIntegralNumber()
                || target.path("schemaRevision").asLong() < 1) throw new VclException("TARGET_INVALID", "");
        UUID id;
        try { id = UUID.fromString(target.path("datasetId").asText()); }
        catch (IllegalArgumentException invalid) { throw new VclException("TARGET_INVALID", ""); }
        return view(reviews.reserve(account(actor), actor.id(), id, target.path("schemaRevision").asLong(), NativeReviewRequests.key(json.path("idempotencyKey"))));
    }

    public View prepare(AuthenticatedUser actor, UUID dataset, NativeReviewRequests.Preparation request) {
        var document = PARSER.parse(request.document());
        if (!document.spec().at("/target/datasetId").asText().equals(dataset.toString())) throw new VclException("TARGET_CONFLICT", "");
        return view(reviews.prepare(account(actor), actor.id(), request.reservationId(), document));
    }

    public View inspect(AuthenticatedUser actor, UUID id) { return view(reviews.inspect(account(actor), actor.id(), id)); }

    public View apply(AuthenticatedUser actor, UUID id, NativeReviewRequests.Application request) {
        UUID account = account(actor);
        var review = reviews.begin(account, actor.id(), id, request.reviewRevision(), request.reviewDigest(), request.idempotencyKey());
        if (review.status().equals("SUCCEEDED") || review.status().equals("FAILED")) return view(review);
        try { return view(mutations.apply(account, actor.id(), id)); }
        catch (VclException failure) {
            if (failure.code().equals("ACCOUNT_ACCESS_REVOKED")) throw failure;
            String status = failure.code().equals("ROW_COMMIT_UNCERTAIN") ? "UNKNOWN" : "FAILED";
            return view(reviews.outcome(account, actor.id(), id, status, failure.code()));
        }
    }

    public View reconcile(AuthenticatedUser actor, UUID id) {
        var review = reviews.inspect(account(actor), actor.id(), id);
        if (!Set.of("APPLYING", "UNKNOWN", "SUCCEEDED", "FAILED").contains(review.status())) throw new VclException("REVIEW_STATE_CONFLICT", "");
        return view(review); // Read-only reconciliation: no unconfirmed redispatch.
    }

    public UUID account(AuthenticatedUser actor) {
        var account = accounts.findByUserId(actor.id()).orElseThrow(() -> new VclException("ACCOUNT_SETUP_REQUIRED", ""));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        return account.getId();
    }

    private static View view(NativeRowReview review) {
        JsonNode result = null;
        if (review.resultJson() != null) {
            try { result = MAPPER.readTree(review.resultJson()); }
            catch (java.io.IOException invalid) { throw new VclException("RECEIPT_INTEGRITY", ""); }
        }
        return new View(review.id(), review.revision(), review.status(), review.expiresAt(), review.datasetId(), review.mutationId(),
                review.createdRecordId(), review.receiptId(), review.semanticDigest(), review.reviewDigest(),
                review.canonicalDocument() == null ? null : PARSER.parse(review.canonicalDocument()).spec(), review.outcomeCode(), result);
    }
}
