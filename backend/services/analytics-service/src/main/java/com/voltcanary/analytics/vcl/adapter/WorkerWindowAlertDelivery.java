package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

/** Delivers immutable alert proposals and records the alert owner's exact receipt. */
@Slf4j
final class WorkerWindowAlertDelivery {

    private static final int PAGE_SIZE = 16;
    private final JdbcKeyedStateStore store;
    private final JdbcRuleDeployments deployments;
    private final ConfigurableOwnerReads alerts;
    private final VclWorkerAssertion signer;
    private final VclParser parser = new VclParser();
    private int offset;

    WorkerWindowAlertDelivery(
        DataSource source,
        RestClient.Builder builder,
        AnalyticsDownstreamProperties downstream,
        VclWorkerIdentityProperties identity
    ) {
        store = new JdbcKeyedStateStore(source);
        deployments = new JdbcRuleDeployments(source, new JdbcCompilationBundleStore(source));
        var target = downstream.getAlertService();
        alerts = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("alert-service");
    }

    @Scheduled(fixedDelayString = "${app.vcl.worker.delay:5s}")
    public void poll() {
        if (signer == null) return;
        var page = store.pendingAlerts(PAGE_SIZE, offset);
        offset = page.size() < PAGE_SIZE || offset + PAGE_SIZE > 1_000_000 ? 0 : offset + PAGE_SIZE;
        for (var pending : page) {
            try {
                var key = pending.key();
                var deployment = deployments
                    .find(key.accountId(), key.deploymentEpoch())
                    .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
                store.deliverAlert(pending, deployment, (proposal, measure) ->
                    send(key, deployment, proposal, measure)
                );
            } catch (RuntimeException unavailable) {
                log.warn(
                    "VCL alert proposal remains pending: {}",
                    unavailable instanceof VclException vcl
                        ? vcl.code()
                        : unavailable.getClass().getSimpleName()
                );
            }
        }
    }

    private KeyedStateStore.Acknowledgement send(
        KeyedStateStore.Key key,
        JdbcRuleDeployments.Deployment deployment,
        KeyedStateStore.Output proposal,
        KeyedStateStore.Output measure
    ) {
        byte[] body = proposal.payload().payload().getBytes(StandardCharsets.UTF_8);
        var payload = parser.readJson(body);
        var result = parser.readJson(measure.payload().payload().getBytes(StandardCharsets.UTF_8));
        String profile = payload.path("profile").asText();
        boolean intervalPrefix =
            SessionAlertProposal.INTERVAL_PREFIX.equals(profile) ||
            SessionAlertProposal.COUNT_PREFIX.equals(profile);
        boolean intervalRetraction =
            SessionAlertProposal.INTERVAL_RETRACTION.equals(profile) ||
            SessionAlertProposal.COUNT_RETRACTION.equals(profile) ||
            SessionAlertProposal.AGGREGATE_RETRACTION.equals(profile);
        boolean intervalCorrection =
            SessionAlertProposal.INTERVAL_CORRECTION.equals(profile) ||
            SessionAlertProposal.COUNT_CORRECTION.equals(profile) ||
            SessionAlertProposal.AGGREGATE_CORRECTION.equals(profile);
        boolean interval =
            intervalPrefix ||
            intervalRetraction ||
            intervalCorrection ||
            SessionAlertProposal.INTERVAL.equals(profile) ||
            SessionAlertProposal.COUNT.equals(profile) ||
            SessionAlertProposal.AGGREGATE.equals(profile);
        boolean sustained = java.util.Set.of(
            SessionAlertProposal.SUSTAINED,
            SessionAlertProposal.SUSTAINED_RETRACTION,
            SessionAlertProposal.SUSTAINED_CORRECTION
        ).contains(profile);
        boolean retraction =
            intervalRetraction ||
            SessionAlertProposal.RETRACTION.equals(profile) ||
            SessionAlertProposal.SUSTAINED_RETRACTION.equals(profile);
        boolean correction =
            intervalCorrection ||
            SessionAlertProposal.CORRECTION.equals(profile) ||
            SessionAlertProposal.SUSTAINED_CORRECTION.equals(profile);
        boolean session =
            SessionAlertProposal.PROFILE.equals(profile) ||
            SessionAlertProposal.RETRACTION.equals(profile) ||
            SessionAlertProposal.CORRECTION.equals(profile);
        boolean occurrenceBased = session || sustained;
        var decision = occurrenceBased ? result : result.path("decision");
        boolean revoked =
            deployment.state() == JdbcRuleDeployments.State.REVOKED ||
            deployment.state() == JdbcRuleDeployments.State.PAUSED;
        Instant openedAt = Instant.parse(payload.path("windowStart").textValue());
        boolean proofPin = "VCL_WINDOW_ALERT_PROPOSAL/2".equals(profile);
        if (
            (!occurrenceBased &&
                !interval &&
                !proofPin &&
                !"VCL_WINDOW_ALERT_PROPOSAL/1".equals(profile)) ||
            !key.accountId().toString().equals(payload.path("account").textValue()) ||
            !key.ruleId().toString().equals(payload.path("rule").textValue()) ||
            !key.deploymentEpoch().toString().equals(payload.path("epoch").textValue()) ||
            !key.subjectDigest().equals(payload.path("subjectDigest").textValue()) ||
            !deployment.bundleDigest().equals(payload.path("bundleDigest").textValue()) ||
            !deployment.grant().toString().equals(payload.path("grant").textValue()) ||
            (interval &&
                (!"vcl-interval-final-measure-1".equals(result.path("profile").textValue()) ||
                    !key.occurrenceDigest().equals(payload.path("occurrenceDigest").textValue()) ||
                    !payload.path("windowStart").equals(result.path("windowStart")) ||
                    !payload.path("windowEnd").equals(result.path("windowEnd")) ||
                    (intervalPrefix &&
                        (!payload.path("measuredThrough").isTextual() ||
                            !payload
                                .path("measuredThrough")
                                .equals(result.path("measuredThrough")))))) ||
            (revoked
                ? openedAt.isBefore(deployment.start().boundary())
                : !deployment.includesWindow(openedAt)) ||
            (occurrenceBased
                ? !(
                      sustained ? "vcl-sustained-final-measure-1" : SessionAbsenceSchedule.MEASURE
                  ).equals(result.path("profile").textValue()) ||
                  (sustained
                      ? !result.path("occurrence").isTextual() ||
                        !result.path("occurrence").equals(payload.path("occurrenceDigest"))
                      : !key
                            .occurrenceDigest()
                            .equals(payload.path("occurrenceDigest").textValue())) ||
                  !payload.path("windowStart").equals(result.path("sessionStart")) ||
                  !payload.path("windowEnd").equals(result.path("sessionEnd"))
                : !result.path("finalized").isBoolean() ||
                  result.path("finalized").booleanValue() == intervalPrefix) ||
            !"OK".equals(decision.path("status").textValue()) ||
            !"BOOLEAN".equals(decision.at("/literal/type").textValue()) ||
            !decision.at("/literal/value").isBoolean() ||
            decision.at("/literal/value").booleanValue() == retraction ||
            ((retraction || correction) &&
                !result.path(sustained ? "correction" : "supersedes").isTextual()) ||
            (retraction && session && !result.path("firstWitness").isTextual()) ||
            (payload.has("observationReferences") &&
                (!session || !matchesObservationReferences(payload, result))) ||
            !payload
                .path("coverageDigest")
                .equals(
                    occurrenceBased || interval
                        ? result.path("coverageDigest")
                        : result.at("/coveragePin/digest")
                ) ||
            (proofPin &&
                (!payload.path("coverageProofDigest").isTextual() ||
                    !payload
                        .path("coverageProofDigest")
                        .equals(result.at("/coveragePin/eventTimeDigest")))) ||
            !payload
                .path("coverageProof")
                .equals(result.path(occurrenceBased || interval ? "proof" : "coverageProof"))
        ) {
            throw new VclException("ALERT_PROPOSAL_SCOPE_CHANGED", "");
        }
        UUID id = proposal.id();
        try {
            byte[] response;
            if (revoked) {
                response = reconcile(key, deployment, id, body);
            } else {
                try {
                    response = request(key, deployment, id, body, "");
                } catch (ConfigurableOwnerReads.Failure uncertain) {
                    response = reconcile(key, deployment, id, body);
                }
            }
            var receipt = parser.readJson(response);
            String outcome = receipt.path("outcome").textValue();
            boolean settlement =
                "RECORDED".equals(outcome) ||
                ("GRANT_CLOSED".equals(outcome) && receipt.path("alertId").isNull());
            if (
                !receipt.isObject() ||
                (outcome == null
                    ? receipt.size() != 4 || receipt.has("outcome")
                    : receipt.size() != 5 || !settlement) ||
                !id.equals(uuid(receipt.path("proposalId").textValue())) ||
                !proposal.payload().digest().equals(receipt.path("proposalDigest").textValue())
            ) {
                throw new VclException("ALERT_EFFECT_RECEIPT_INVALID", "");
            }
            if (!receipt.path("alertId").isNull()) uuid(receipt.path("alertId").textValue());
            return new KeyedStateStore.Acknowledgement(
                id,
                proposal.digest(),
                uuid(receipt.path("receiptId").textValue()),
                VclCanonical.sha256(VclCanonical.encode(receipt))
            );
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    private static boolean matchesObservationReferences(
        com.fasterxml.jackson.databind.JsonNode proposal,
        com.fasterxml.jackson.databind.JsonNode measure
    ) {
        var references = proposal.path("observationReferences");
        var available = SessionAlertProposal.observationReferences(measure);
        if (
            !references.isArray() || references.isEmpty() || references.size() > available.size()
        ) return false;
        for (int i = 0; i < references.size(); i++) {
            if (!available.get(i).equals(references.get(i).textValue())) return false;
        }
        return true;
    }

    private byte[] reconcile(
        KeyedStateStore.Key key,
        JdbcRuleDeployments.Deployment deployment,
        UUID id,
        byte[] body
    ) {
        try {
            return request(key, deployment, id, body, "/receipt");
        } catch (ConfigurableOwnerReads.Failure missing) {
            if (!"ALERT_EFFECT_RECEIPT_UNAVAILABLE".equals(missing.code())) throw missing;
            return request(key, deployment, id, body, "/settlement");
        }
    }

    private byte[] request(
        KeyedStateStore.Key key,
        JdbcRuleDeployments.Deployment deployment,
        UUID id,
        byte[] body,
        String suffix
    ) {
        var path = "/internal/vcl/window-alert-proposals/" + id + suffix;
        String operation = switch (suffix) {
            case "/receipt" -> "WINDOW_ALERT_RECEIPT";
            case "/settlement" -> "WINDOW_ALERT_SETTLEMENT";
            default -> "WINDOW_ALERT_EFFECT";
        };
        var assertion = signer.sign(
            new VclWorkerAssertion.Scope(
                deployment.actor(),
                key.accountId(),
                key.ruleId(),
                key.deploymentEpoch(),
                deployment.grant(),
                id,
                operation,
                VclWorkerAssertion.requestDigest("POST", path, body)
            ),
            20
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        return switch (suffix) {
            case "/receipt" -> alerts.workerWindowAlertReceipt(assertion, id, body, deadline);
            case "/settlement" -> alerts.workerWindowAlertSettlement(assertion, id, body, deadline);
            default -> alerts.workerWindowAlertEffect(assertion, id, body, deadline);
        };
    }

    private static UUID uuid(String text) {
        try {
            var id = UUID.fromString(text);
            if (!id.toString().equals(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("ALERT_EFFECT_RECEIPT_INVALID", "");
        }
    }
}
