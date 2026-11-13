package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.VclWorkerIdentityProperties;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclDatasetDescriptions;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Renews the exact source/dataset grant pins using only scoped, short-lived machine assertions. */
@Component
public final class WorkerGrantScopeAuthority {
    private final ConfigurableOwnerReads reads;
    private final VclWorkerAssertion signer;

    public WorkerGrantScopeAuthority(RestClient.Builder builder, AlertAuthorizationProperties properties,
            VclWorkerIdentityProperties identity) {
        reads = new ConfigurableOwnerReads(builder, properties.getDeviceServiceBaseUrl(), properties.getTimeout());
        signer = identity.forAudience("device-service");
    }

    public JsonNode validate(VclWorkerAssertion.Scope worker, AutomationGrantScope scope) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var pins = JsonNodeFactory.instance.arrayNode();
        String ownerScope = null;
        try {
            for (var target : scope.sourceBindings()) {
                var path = "/internal/vcl/source-bindings/" + target.id() + "/capture?schemaRevision=" + target.schemaRevision();
                var assertion = sign(worker, target.id(), "SOURCE_CAPTURE", "GET", path);
                var value = VclSourceCaptureDescription.parse(reads.workerSourceCapture(
                        assertion, target.id(), target.schemaRevision(), deadline)).source();
                if (!worker.actor().equals(value.actorId()) || !worker.account().equals(value.accountId())
                        || !target.id().equals(value.id()) || value.schemaRevision() != target.schemaRevision()) {
                    throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                }
                if (!value.sourceActive() || value.schemaRevision() != value.currentSchemaRevision()) {
                    throw new VclException("GRANT_SCOPE_CHANGED", "");
                }
                if (ownerScope != null && !ownerScope.equals(value.accessScopeDigest())) {
                    throw new VclException("GRANT_SCOPE_CHANGED", "");
                }
                ownerScope = value.accessScopeDigest();
                var allowed = new HashSet<UUID>();
                value.definition().path("fields").forEach(field -> allowed.add(UUID.fromString(field.path("id").asText())));
                if (!allowed.containsAll(target.fieldIds())) throw new VclException("GRANT_FIELD_UNAVAILABLE", "");
                pins.addObject().put("owner", "device-service").put("kind", "SOURCE_BINDING")
                        .put("resourceId", target.id().toString()).put("resourceRevision", target.schemaRevision())
                        .put("schemaRevision", target.schemaRevision()).put("snapshotId", value.snapshotId().toString())
                        .put("accessScopeDigest", value.accessScopeDigest()).put("definitionDigest", value.definitionDigest())
                        .put("sourceStateDigest", value.sourceStateDigest()).putNull("checkpoint");
            }
            for (var target : scope.datasets()) {
                var path = "/internal/vcl/datasets/" + target.id() + "?schemaRevision=" + target.schemaRevision();
                var assertion = sign(worker, target.id(), "DATASET_DESCRIPTION", "GET", path);
                var value = VclDatasetDescriptions.parse(reads.workerDatasetDescription(
                        assertion, target.id(), target.schemaRevision(), deadline));
                if (!target.id().equals(value.id()) || target.schemaRevision() != value.schemaRevision()
                        || !value.accessScopeDigest().equals(ownerScope)) throw new VclException("GRANT_SCOPE_CHANGED", "");
                var allowed = new HashSet<UUID>();
                value.definition().path("fields").forEach(field -> allowed.add(UUID.fromString(field.path("id").asText())));
                allowed.add(UUID.fromString(value.definition().at("/systemFields/recordIdFieldId").asText()));
                if (!allowed.containsAll(target.fieldIds())) throw new VclException("GRANT_FIELD_UNAVAILABLE", "");
                pins.addObject().put("owner", "device-service").put("kind", "DATASET")
                        .put("resourceId", target.id().toString()).put("resourceRevision", target.schemaRevision())
                        .put("schemaRevision", target.schemaRevision()).put("accessScopeDigest", value.accessScopeDigest())
                        .put("schemaDigest", value.schemaDigest()).put("generation", value.generation().toString())
                        .putNull("snapshotId").putNull("checkpoint");
            }
            return pins;
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        catch (VclException failure) { throw failure; }
        catch (IllegalArgumentException | ClassCastException failure) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
    }

    private String sign(VclWorkerAssertion.Scope worker, UUID target, String operation, String method, String path) {
        return signer.sign(new VclWorkerAssertion.Scope(worker.actor(), worker.account(), worker.rule(), worker.epoch(),
                worker.grant(), target, operation, VclWorkerAssertion.requestDigest(method, path, new byte[0])), 20);
    }

    UUID verifyWindowSource(VclWorkerAssertion.Scope worker, WindowAlertProposal proposal) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        UUID binding = proposal.binding();
        long revision = proposal.schemaRevision();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        try {
            var path = "/internal/vcl/source-bindings/" + binding + "/capture?schemaRevision=" + revision;
            var capture = readCapture(worker, binding, revision, path, deadline);
            var pin = capture.capture().pin();
            if (!worker.account().equals(pin.accountId()) || !worker.actor().equals(capture.source().actorId())
                    || !binding.equals(pin.bindingId()) || revision != pin.schemaRevision()
                    || !capture.source().sourceActive() || !capture.capture().journalReady()
                    || !proposal.uuid("snapshot").equals(pin.snapshotId())
                    || !proposal.uuid("generation").equals(capture.capture().inputGeneration())) {
                throw new VclException("ALERT_EVIDENCE_CHANGED", "");
            }
            byte[] body = com.voltcanary.vcl.VclCanonical.encode(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(pin))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            UUID proofId = proposal.uuid("coverageProof");
            var proofPath = "/internal/vcl/source-bindings/" + binding + "/coverage/" + proofId;
            var proofAssertion = signer.sign(new VclWorkerAssertion.Scope(worker.actor(), worker.account(), worker.rule(),
                    worker.epoch(), worker.grant(), binding, "SOURCE_COVERAGE",
                    VclWorkerAssertion.requestDigest("POST", proofPath, body)), 20);
            var proof = com.voltcanary.vcl.VclSourceCoverage.parse(reads.workerSourceCoverage(
                    proofAssertion, binding, proofId, body, deadline));
            var coverageEnd = proposal.effectEnd();
            if (proposal.interval()) {
                var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(capture.source().definition())
                        .orElseThrow(() -> new VclException("ALERT_EVIDENCE_CHANGED", ""));
                coverageEnd = coverageEnd.plus(contract.maxSpanUs(), java.time.temporal.ChronoUnit.MICROS);
            }
            String expectedProofDigest = proposal.expectedProofDigest();
            if (!proof.qualifiesEventTime() || !proofId.equals(proof.proofId()) || !pin.snapshotId().equals(proof.snapshotId())
                    || (expectedProofDigest != null && !expectedProofDigest.equals(proof.eventTimeDigest()))
                    || !capture.capture().inputGeneration().equals(proof.inputGeneration())
                    || proof.evidence().from().isAfter(proposal.start()) || proof.evidence().until().isBefore(coverageEnd)
                    || com.voltcanary.vcl.VclObservationReadiness.checkpoint(proof.requiredCheckpoint())
                            > com.voltcanary.vcl.VclObservationReadiness.checkpoint(capture.capture().headCheckpoint())) {
                throw new VclException("ALERT_EVIDENCE_CHANGED", "");
            }
            var renewed = readCapture(worker, binding, revision, path, deadline);
            if (!capture.source().equals(renewed.source()) || !pin.equals(renewed.capture().pin())
                    || !renewed.capture().journalReady()
                    || !capture.capture().inputGeneration().equals(renewed.capture().inputGeneration())
                    || com.voltcanary.vcl.VclObservationReadiness.checkpoint(renewed.capture().headCheckpoint())
                            < com.voltcanary.vcl.VclObservationReadiness.checkpoint(capture.capture().headCheckpoint())) {
                throw new VclException("ALERT_EVIDENCE_CHANGED", "");
            }
            return pin.deviceId();
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    private VclSourceCaptureDescription readCapture(VclWorkerAssertion.Scope worker, UUID binding, long revision,
            String path, long deadline) {
        return VclSourceCaptureDescription.parse(reads.workerSourceCapture(
                sign(worker, binding, "SOURCE_CAPTURE", "GET", path), binding, revision, deadline));
    }
}
