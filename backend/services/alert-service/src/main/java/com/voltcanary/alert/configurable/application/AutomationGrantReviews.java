package com.voltcanary.alert.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.configurable.adapter.WorkerGrantScopeAuthority;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.domain.GrantReview;
import com.voltcanary.alert.configurable.domain.GrantRevocation;
import com.voltcanary.alert.configurable.port.GrantReviewStore;
import com.voltcanary.alert.configurable.port.GrantRevocationStore;
import com.voltcanary.alert.configurable.port.GrantScopeAuthority;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One owner lifecycle: preview, exact confirmation, durable receipt recovery and monotone revoke. */
@Service
@RequiredArgsConstructor
public class AutomationGrantReviews {

    public record Created(UUID grantId, UUID ruleId, String createdEpoch) {}

    public record View(
        UUID reviewId,
        UUID grantId,
        UUID ruleId,
        UUID receiptId,
        long revision,
        String status,
        Instant expiresAt,
        String reviewDigest,
        JsonNode definition,
        JsonNode readSet,
        String outcomeCode,
        Created result
    ) {}

    public record GrantView(
        UUID id,
        UUID accountId,
        UUID actorId,
        UUID ruleId,
        String profile,
        String epoch,
        String status,
        Instant expiresAt,
        String definitionDigest,
        JsonNode definition,
        List<String> effects,
        List<UUID> deliveryChannels
    ) {}

    public record RevocationView(
        UUID id,
        UUID receiptId,
        UUID grantId,
        String status,
        String resultEpoch,
        String outcomeCode
    ) {}

    private final RuleAccountAuthority accounts;
    private final GrantScopeAuthority scopes;
    private final GrantReviewStore reviews;
    private final GrantRevocationStore revocations;
    private final WorkerGrantScopeAuthority workerScopes;
    private final VclParser parser = new VclParser();

    public View reserve(UUID actor, byte[] body) {
        var request = AutomationGrantRequests.reserve(body);
        return view(
            reviews.reserve(accounts.account(actor), actor, request.ruleReviewId(), request.key())
        );
    }

    public View prepare(UUID actor, String authorization, byte[] body) {
        var request = AutomationGrantRequests.prepare(body);
        UUID account = accounts.account(actor);
        reviews.inspect(account, actor, request.reservationId());
        var pins = scopes.validate(authorization, actor, account, request.scope());
        return view(
            reviews.prepare(account, actor, request.reservationId(), request.scope(), pins)
        );
    }

    public View inspect(UUID actor, UUID id) {
        return view(reviews.inspect(accounts.account(actor), actor, id));
    }

    public View apply(UUID actor, String authorization, UUID id, byte[] body) {
        var command = AutomationGrantRequests.apply(body);
        UUID account = accounts.account(actor);
        var existing = reviews.inspect(account, actor, id);
        if (
            existing.revision() != command.revision() ||
            !Objects.equals(existing.reviewDigest(), command.digest())
        ) throw new VclException("REVIEW_CONFLICT", "");
        if (!existing.reservationKey().equals(command.key())) throw new VclException(
            "IDEMPOTENCY_KEY_REUSED",
            ""
        );
        if (existing.status().equals("SUCCEEDED")) return view(existing);
        if (!List.of("PREPARED", "APPLYING", "UNKNOWN").contains(existing.status())) {
            throw new VclException(
                existing.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT",
                ""
            );
        }
        var scope = AutomationGrantScope.parse(json(existing.canonicalDefinition()));
        var pins = scopes.validate(authorization, actor, account, scope);
        if (!VclCanonical.encode(pins).equals(existing.canonicalPins())) throw new VclException(
            "GRANT_SCOPE_CHANGED",
            ""
        );
        var started = reviews.begin(
            account,
            actor,
            id,
            command.revision(),
            command.digest(),
            command.key()
        );
        if (started.status().equals("SUCCEEDED")) return view(started);
        try {
            return view(reviews.complete(account, actor, id));
        } catch (RuntimeException failure) {
            String code =
                failure instanceof VclException typed ? typed.code() : "GRANT_EFFECT_UNCERTAIN";
            try {
                return view(reviews.unknown(account, actor, id, code));
            } catch (RuntimeException unavailable) {
                return view(started, "UNKNOWN", code);
            }
        }
    }

    public View reconcile(UUID actor, UUID id, byte[] body) {
        AutomationGrantRequests.empty(body);
        return view(reviews.reconcile(accounts.account(actor), actor, id));
    }

    public GrantView grant(UUID actor, String authorization, UUID id) {
        UUID account = accounts.account(actor);
        var grant = reviews.grant(account, actor, id);
        var scope = AutomationGrantScope.parse(json(grant.canonicalDefinition()));
        if (
            grant.status().equals("ACTIVE") &&
            !VclCanonical.encode(scopes.validate(authorization, actor, account, scope)).equals(
                grant.canonicalPins()
            )
        ) {
            throw new VclException("GRANT_SCOPE_CHANGED", "");
        }
        return new GrantView(
            id,
            account,
            actor,
            grant.ruleId(),
            AutomationGrantScope.PROFILE,
            Long.toString(grant.epoch()),
            grant.status(),
            grant.expiresAt(),
            grant.definitionDigest(),
            scope.json(),
            List.of("ALERT"),
            List.of()
        );
    }

    public GrantView machineGrant(VclWorkerAssertion.Scope worker, long expectedEpoch) {
        if (expectedEpoch < 1 || !worker.grant().equals(worker.target())) {
            throw new VclException("GRANT_TARGET_INVALID", "");
        }
        UUID account = accounts.account(worker.actor());
        if (!account.equals(worker.account())) throw new VclException("OWNER_ACCESS_REVOKED", "");
        var grant = reviews.grant(account, worker.actor(), worker.grant());
        if (
            !grant.status().equals("ACTIVE") ||
            grant.epoch() != expectedEpoch ||
            !grant.ruleId().equals(worker.rule())
        ) throw new VclException("AUTOMATION_GRANT_INACTIVE", "");
        var scope = AutomationGrantScope.parse(json(grant.canonicalDefinition()));
        if (
            !VclCanonical.encode(workerScopes.validate(worker, scope)).equals(grant.canonicalPins())
        ) {
            throw new VclException("GRANT_SCOPE_CHANGED", "");
        }
        if (
            !account.equals(accounts.account(worker.actor())) ||
            !grant.equals(reviews.grant(account, worker.actor(), worker.grant()))
        ) {
            throw new VclException("GRANT_CHANGED", "");
        }
        return new GrantView(
            grant.id(),
            account,
            worker.actor(),
            grant.ruleId(),
            AutomationGrantScope.PROFILE,
            Long.toString(grant.epoch()),
            grant.status(),
            grant.expiresAt(),
            grant.definitionDigest(),
            scope.json(),
            List.of("ALERT"),
            List.of()
        );
    }

    public RevocationView revoke(UUID actor, UUID id, byte[] body) {
        var request = AutomationGrantRequests.revoke(body);
        // Removing an owned permission remains possible after account/source access has been revoked.
        var started = revocations.reserve(actor, id, request.epoch(), request.key());
        if (List.of("SUCCEEDED", "FAILED").contains(started.status())) return revocation(started);
        try {
            return revocation(revocations.complete(actor, started.id()));
        } catch (RuntimeException failure) {
            try {
                return revocation(revocations.unknown(actor, started.id()));
            } catch (RuntimeException unavailable) {
                return new RevocationView(
                    started.id(),
                    started.receiptId(),
                    id,
                    "UNKNOWN",
                    null,
                    "GRANT_EFFECT_UNCERTAIN"
                );
            }
        }
    }

    public RevocationView revocation(UUID actor, UUID id) {
        return revocation(revocations.inspect(actor, id));
    }

    private static RevocationView revocation(GrantRevocation value) {
        return new RevocationView(
            value.id(),
            value.receiptId(),
            value.grantId(),
            value.status(),
            value.resultEpoch() == null ? null : value.resultEpoch().toString(),
            value.status().equals("FAILED") ? "GRANT_EPOCH_CONFLICT" : null
        );
    }

    private View view(GrantReview value) {
        return view(value, value.status(), value.outcomeCode());
    }

    private View view(GrantReview value, String status, String code) {
        return new View(
            value.id(),
            value.grantId(),
            value.ruleId(),
            value.receiptId(),
            value.revision(),
            status,
            value.expiresAt(),
            value.reviewDigest(),
            value.canonicalDefinition() == null ? null : json(value.canonicalDefinition()),
            value.canonicalPins() == null ? null : json(value.canonicalPins()),
            code,
            status.equals("SUCCEEDED") ? new Created(value.grantId(), value.ruleId(), "1") : null
        );
    }

    private JsonNode json(String value) {
        return parser.readJson(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
