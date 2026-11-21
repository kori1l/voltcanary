package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclSourceCoverage;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclDatasetDescriptions;
import com.voltcanary.vcl.VclObservationReadiness;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads one current source cut under a durable active deployment, never a saved user bearer. */
@Component
public final class WorkerSourceCaptureReads {
    private final JdbcRuleDeployments deployments;
    private final JdbcCompilationBundleStore bundles;
    private final ConfigurableOwnerReads reads;
    private final VclWorkerAssertion signer;
    private final WorkerGrantReads grants;

    public WorkerSourceCaptureReads(DataSource source, RestClient.Builder builder, AnalyticsDownstreamProperties properties,
            VclWorkerIdentityProperties identity, WorkerGrantReads grants) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        var target = properties.getDeviceService();
        reads = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("device-service");
        this.grants = grants;
    }

    public VclSourceCaptureDescription capture(UUID account, UUID epoch, UUID binding, long revision) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        var active = active(account, epoch, binding, revision);
        var generation = requirePinned(active, binding, revision);
        grants.read(account, epoch);
        var path = "/internal/vcl/source-bindings/" + binding + "/capture?schemaRevision=" + revision;
        var assertion = sign(active, binding, "SOURCE_CAPTURE", "GET", path, new byte[0]);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclSourceCaptureDescription result;
        try { result = VclSourceCaptureDescription.parse(reads.workerSourceCapture(assertion, binding, revision, deadline)); }
        catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        var source = result.source();
        if (!source.actorId().equals(active.actor()) || !source.accountId().equals(account)
                || !source.id().equals(binding) || source.schemaRevision() != revision || !source.sourceActive()) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        if (!generation.equals(result.capture().inputGeneration())) {
            throw new VclException("WORKER_INPUT_CHANGED", "");
        }
        recheck(active);
        grants.read(account, epoch);
        return result;
    }

    public VclDatasetDescription datasetDescription(UUID account, UUID epoch, UUID dataset, long revision) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        if (account == null || epoch == null || dataset == null || revision < 1) {
            throw new VclException("WORKER_TARGET_INVALID", "");
        }
        var active = deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!active.permitsOwnerInspection()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        String expectedDigest = requirePinnedDataset(active, dataset, revision);
        grants.read(account, epoch);
        var path = "/internal/vcl/datasets/" + dataset + "?schemaRevision=" + revision;
        var assertion = sign(active, dataset, "DATASET_DESCRIPTION", "GET", path, new byte[0]);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclDatasetDescription result;
        try { result = VclDatasetDescriptions.parse(reads.workerDatasetDescription(assertion, dataset, revision, deadline)); }
        catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        var prepared = (com.fasterxml.jackson.databind.node.ObjectNode) result.definition();
        prepared.remove(java.util.List.of("generation", "dataVersion"));
        prepared.putObject("runtimeProjection").put("supportsAsOf", true);
        if (!dataset.equals(result.id()) || revision != result.schemaRevision() || !result.liveQueryReady()
                || !expectedDigest.equals(com.voltcanary.vcl.VclCanonical.sha256(
                        com.voltcanary.vcl.VclCanonical.encode(prepared)))) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        recheck(active);
        grants.read(account, epoch);
        return result;
    }

    public VclObservationPage observations(UUID account, UUID epoch, UUID binding, long revision, VclObservationRead request) {
        if (request == null) throw new VclException("WORKER_TARGET_INVALID", "");
        var capture = capture(account, epoch, binding, revision);
        if (!capture.capture().journalReady() || !capture.capture().pin().equals(request.pin())
                || !capture.capture().inputGeneration().equals(request.inputGeneration())) {
            throw new VclException("WORKER_INPUT_CHANGED", "");
        }
        var active = active(account, epoch, binding, revision);
        if (!active.acceptsExistingWork()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        byte[] body = request.bytes();
        var path = "/internal/vcl/source-bindings/" + binding + "/observations";
        var assertion = sign(active, binding, "SOURCE_OBSERVATIONS", "POST", path, body);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclObservationPage result;
        try { result = VclObservationPage.parse(reads.workerSourceObservations(assertion, binding, body, deadline)); }
        catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        if (!request.equals(result.request())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        result.requireSchema(capture.source().definition());
        recheck(active);
        grants.read(account, epoch);
        return result;
    }

    public VclSourceCoverage coverage(UUID account, UUID epoch, UUID binding, long revision,
            UUID proof, VclObservationReadiness.Pin pin) {
        if (proof == null || pin == null) throw new VclException("WORKER_TARGET_INVALID", "");
        var capture = capture(account, epoch, binding, revision);
        if (!capture.capture().pin().equals(pin) || !capture.capture().journalReady()) {
            throw new VclException("WORKER_INPUT_CHANGED", "");
        }
        var active = active(account, epoch, binding, revision);
        byte[] body = com.voltcanary.vcl.VclCanonical.encode(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(pin))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var path = "/internal/vcl/source-bindings/" + binding + "/coverage/" + proof;
        var assertion = sign(active, binding, "SOURCE_COVERAGE", "POST", path, body);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclSourceCoverage result;
        try { result = VclSourceCoverage.parse(reads.workerSourceCoverage(assertion, binding, proof, body, deadline)); }
        catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        if (!proof.equals(result.proofId()) || !pin.snapshotId().equals(result.snapshotId())
                || "COVERED".equals(result.coverage()) && !capture.capture().inputGeneration().equals(result.inputGeneration())) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        recheck(active);
        grants.read(account, epoch);
        return result;
    }

    UUID findCoverage(UUID account, UUID epoch, UUID binding, long revision, UUID generation,
            java.time.Instant from, java.time.Instant until) {
        return findCoverage(account, epoch, binding, revision, generation, from, until, 0);
    }

    UUID findCoverage(UUID account, UUID epoch, UUID binding, long revision, UUID generation,
            java.time.Instant from, java.time.Instant until, long minimumCheckpoint) {
        var capture = capture(account, epoch, binding, revision);
        var active = active(account, epoch, binding, revision);
        if (!active.acceptsExistingWork() || !capture.capture().journalReady()
                || !generation.equals(capture.capture().inputGeneration())) {
            throw new VclException("WORKER_INPUT_CHANGED", "");
        }
        byte[] body = new com.voltcanary.vcl.VclCoverageSearch(capture.capture().pin(), generation, from, until,
                minimumCheckpoint).bytes();
        var path = "/internal/vcl/source-bindings/" + binding + "/coverage-search";
        var assertion = sign(active, binding, "SOURCE_COVERAGE_SEARCH", "POST", path, body);
        try {
            UUID proof = com.voltcanary.vcl.VclCoverageSearch.parseResult(reads.workerSourceCoverageSearch(assertion,
                    binding, body, System.nanoTime() + Duration.ofSeconds(20).toNanos()));
            recheck(active);
            grants.read(account, epoch);
            return proof;
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
    }

    private JdbcRuleDeployments.Deployment active(UUID account, UUID epoch, UUID binding, long revision) {
        if (account == null || epoch == null || binding == null || revision < 1) throw new VclException("WORKER_TARGET_INVALID", "");
        var value = deployments.find(account, epoch).orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!value.permitsOwnerInspection()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        return value;
    }

    private String sign(JdbcRuleDeployments.Deployment active, UUID binding, String operation, String method,
            String path, byte[] body) {
        return signer.sign(new VclWorkerAssertion.Scope(active.actor(), active.account(), active.rule(),
                active.epoch(), active.grant(), binding, operation,
                VclWorkerAssertion.requestDigest(method, path, body)), 20);
    }

    private void recheck(JdbcRuleDeployments.Deployment active) {
        if (!active.equals(deployments.find(active.account(), active.epoch())
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", "")))) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
    }

    private UUID requirePinned(JdbcRuleDeployments.Deployment active, UUID binding, long revision) {
        var bundle = bundles.find(active.account(), active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var compiled = bundle.json()
                .path("compilation");
        for (var node : compiled.path("graph")) {
            var config = node.path("config");
            if ("read_stream".equals(node.path("method").textValue())
                    && binding.toString().equals(config.path("bindingId").textValue())
                    && revision == config.path("schemaRevision").longValue()) return bundle.inputGeneration(binding);
        }
        throw new VclException("WORKER_SOURCE_NOT_PINNED", "");
    }

    private String requirePinnedDataset(JdbcRuleDeployments.Deployment active, UUID dataset, long revision) {
        var bundle = bundles.find(active.account(), active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var compiled = bundle.json()
                .path("compilation");
        for (var node : compiled.path("graph")) {
            if (!"lookup_asof".equals(node.path("method").textValue())) continue;
            var target = node.path("config").path("target");
            if (dataset.toString().equals(target.path("datasetId").textValue())
                    && revision == target.path("schemaRevision").longValue()) {
                for (var dependency : compiled.path("dependencies")) {
                    if ("DATASET".equals(dependency.path("kind").textValue())
                            && dataset.toString().equals(dependency.path("id").textValue())
                            && revision == dependency.path("revision").longValue()) {
                        String digest = dependency.path("snapshotDigest").textValue();
                        if (digest != null && digest.matches("[0-9a-f]{64}")) return digest;
                    }
                }
                throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
            }
        }
        throw new VclException("WORKER_DATASET_NOT_PINNED", "");
    }
}
