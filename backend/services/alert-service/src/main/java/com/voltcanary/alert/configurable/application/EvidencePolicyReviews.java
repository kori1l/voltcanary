package com.voltcanary.alert.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition;
import com.voltcanary.alert.configurable.domain.EvidencePolicyReview;
import com.voltcanary.alert.configurable.port.EvidencePolicyStore;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvidencePolicyReviews {

    public record Created(UUID policyId, UUID ruleId, String revision) {}

    public record View(
        UUID reviewId,
        UUID policyId,
        UUID receiptId,
        UUID grantId,
        UUID ruleId,
        String grantEpoch,
        String grantDefinitionDigest,
        long revision,
        String status,
        Instant expiresAt,
        String reviewDigest,
        JsonNode definition,
        String outcomeCode,
        Created result
    ) {}

    public record PolicyView(
        UUID id,
        UUID accountId,
        UUID actorId,
        UUID ruleId,
        UUID grantId,
        String grantEpoch,
        String grantDefinitionDigest,
        String profile,
        long revision,
        String status,
        boolean usable,
        Instant expiresAt,
        String definitionDigest,
        JsonNode definition
    ) {}

    private final RuleAccountAuthority accounts;
    private final AutomationGrantReviews grants;
    private final EvidencePolicyStore store;
    private final VclParser parser = new VclParser();

    public View reserve(UUID actor, String authorization, byte[] body) {
        var request = EvidencePolicyRequests.reserve(body);
        UUID account = accounts.account(actor);
        var replay = store.reservation(
            account,
            actor,
            request.grantId(),
            request.epoch(),
            request.key()
        );
        if (replay.isPresent()) return view(replay.get());
        // Fresh source/native checks are supplied by the actual grant owner use case.
        var grant = grants.grant(actor, authorization, request.grantId());
        requireGrant(grant, account, request.epoch());
        return view(
            store.reserve(account, actor, request.grantId(), request.epoch(), request.key())
        );
    }

    public View prepare(UUID actor, String authorization, byte[] body) {
        var request = EvidencePolicyRequests.prepare(body);
        UUID account = accounts.account(actor);
        var review = store.inspect(account, actor, request.reservationId());
        requireGrant(
            grants.grant(actor, authorization, review.grantId()),
            account,
            review.grantEpoch()
        );
        return view(store.prepare(account, actor, review.id(), request.definition()));
    }

    public View inspect(UUID actor, UUID id) {
        return view(store.inspect(accounts.account(actor), actor, id));
    }

    public View apply(UUID actor, String authorization, UUID id, byte[] body) {
        var command = AutomationGrantRequests.apply(body);
        UUID account = accounts.account(actor);
        var review = store.inspect(account, actor, id);
        if (
            review.revision() != command.revision() ||
            !Objects.equals(review.reviewDigest(), command.digest())
        ) throw new VclException("REVIEW_CONFLICT", "");
        if (!review.reservationKey().equals(command.key())) throw new VclException(
            "IDEMPOTENCY_KEY_REUSED",
            ""
        );
        if (review.status().equals("SUCCEEDED")) return view(review);
        if (!List.of("PREPARED", "APPLYING", "UNKNOWN").contains(review.status())) {
            throw new VclException(
                review.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT",
                ""
            );
        }
        requireGrant(
            grants.grant(actor, authorization, review.grantId()),
            account,
            review.grantEpoch()
        );
        var started = store.begin(
            account,
            actor,
            id,
            command.revision(),
            command.digest(),
            command.key()
        );
        if (started.status().equals("SUCCEEDED")) return view(started);
        try {
            return view(store.complete(account, actor, id));
        } catch (RuntimeException failure) {
            String code =
                failure instanceof VclException typed
                    ? typed.code()
                    : "EVIDENCE_POLICY_EFFECT_UNCERTAIN";
            try {
                return view(store.unknown(account, actor, id, code));
            } catch (RuntimeException unavailable) {
                return view(started, "UNKNOWN", code);
            }
        }
    }

    public View reconcile(UUID actor, UUID id, byte[] body) {
        AutomationGrantRequests.empty(body);
        return view(store.reconcile(accounts.account(actor), actor, id));
    }

    public PolicyView policy(UUID actor, String authorization, UUID id) {
        UUID account = accounts.account(actor);
        var original = store.policy(account, actor, id).policy();
        var grant = grants.grant(actor, authorization, original.grantId());
        // Renew the local epoch after remote owner checks; a concurrent revoke cannot be hidden by their latency.
        var current = store.policy(account, actor, id);
        var policy = current.policy();
        boolean usable =
            current.usable() &&
            grant.status().equals("ACTIVE") &&
            grant.epoch().equals(Long.toString(policy.grantEpoch())) &&
            grant.accountId().equals(account) &&
            grant.definitionDigest().equals(policy.grantDefinitionDigest());
        return new PolicyView(
            id,
            account,
            actor,
            policy.ruleId(),
            policy.grantId(),
            Long.toString(policy.grantEpoch()),
            policy.grantDefinitionDigest(),
            EvidencePolicyDefinition.PROFILE,
            1,
            usable ? "ACTIVE" : "INACTIVE",
            usable,
            policy.expiresAt(),
            policy.definitionDigest(),
            json(policy.canonicalDefinition())
        );
    }

    public PolicyView machinePolicy(VclWorkerAssertion.Scope worker, UUID id) {
        UUID account = accounts.account(worker.actor());
        if (!account.equals(worker.account()) || !id.equals(worker.target())) {
            throw new VclException("OWNER_ACCESS_REVOKED", "");
        }
        var original = store.policy(account, worker.actor(), id).policy();
        if (
            !original.ruleId().equals(worker.rule()) || !original.grantId().equals(worker.grant())
        ) {
            throw new VclException("EVIDENCE_POLICY_UNAVAILABLE", "");
        }
        var grantScope = new VclWorkerAssertion.Scope(
            worker.actor(),
            account,
            worker.rule(),
            worker.epoch(),
            worker.grant(),
            worker.grant(),
            "GRANT_DESCRIPTION",
            VclWorkerAssertion.requestDigest(
                "GET",
                "/internal/vcl/grants/" + worker.grant() + "?grantEpoch=" + original.grantEpoch(),
                new byte[0]
            )
        );
        var grant = grants.machineGrant(grantScope, original.grantEpoch());
        var current = store.policy(account, worker.actor(), id);
        var policy = current.policy();
        if (
            !original.equals(policy) ||
            !current.usable() ||
            !grant.status().equals("ACTIVE") ||
            !grant.epoch().equals(Long.toString(policy.grantEpoch())) ||
            !grant.definitionDigest().equals(policy.grantDefinitionDigest()) ||
            !account.equals(accounts.account(worker.actor()))
        ) {
            throw new VclException("EVIDENCE_POLICY_INACTIVE", "");
        }
        return new PolicyView(
            id,
            account,
            worker.actor(),
            policy.ruleId(),
            policy.grantId(),
            Long.toString(policy.grantEpoch()),
            policy.grantDefinitionDigest(),
            EvidencePolicyDefinition.PROFILE,
            1,
            "ACTIVE",
            true,
            policy.expiresAt(),
            policy.definitionDigest(),
            json(policy.canonicalDefinition())
        );
    }

    private static void requireGrant(
        AutomationGrantReviews.GrantView grant,
        UUID account,
        long epoch
    ) {
        if (!grant.accountId().equals(account)) throw new VclException("GRANT_UNAVAILABLE", "");
        if (!grant.status().equals("ACTIVE")) throw new VclException(
            "AUTOMATION_GRANT_INACTIVE",
            ""
        );
        if (!grant.epoch().equals(Long.toString(epoch))) throw new VclException(
            "GRANT_EPOCH_CONFLICT",
            ""
        );
    }

    private View view(EvidencePolicyReview value) {
        return view(value, value.status(), value.outcomeCode());
    }

    private View view(EvidencePolicyReview value, String status, String code) {
        return new View(
            value.id(),
            value.policyId(),
            value.receiptId(),
            value.grantId(),
            value.ruleId(),
            Long.toString(value.grantEpoch()),
            value.grantDefinitionDigest(),
            value.revision(),
            status,
            value.expiresAt(),
            value.reviewDigest(),
            value.canonicalDefinition() == null ? null : json(value.canonicalDefinition()),
            code,
            status.equals("SUCCEEDED") ? new Created(value.policyId(), value.ruleId(), "1") : null
        );
    }

    private JsonNode json(String value) {
        return parser.readJson(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
