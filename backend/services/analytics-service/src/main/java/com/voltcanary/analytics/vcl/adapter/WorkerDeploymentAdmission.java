package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Fresh read-only admission for an ACTIVE deployment; it never consumes a timer or writes an effect. */
public final class WorkerDeploymentAdmission {
    public record Inspection(JdbcRuleDeployments.Deployment deployment,
            Map<UUID, VclSourceCaptureDescription> sources, Map<UUID, VclDatasetDescription> datasets,
            boolean ruleExecutionReady) { }

    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final WorkerGrantReads grants;
    private final WorkerPolicyReads policies;
    private final WorkerSourceCaptureReads owners;

    public WorkerDeploymentAdmission(DataSource source, WorkerGrantReads grants, WorkerPolicyReads policies,
            WorkerSourceCaptureReads owners) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        this.grants = grants;
        this.policies = policies;
        this.owners = owners;
    }

    WorkerDeploymentAdmission(JdbcCompilationBundleStore bundles, JdbcRuleDeployments deployments, WorkerGrantReads grants,
            WorkerPolicyReads policies, WorkerSourceCaptureReads owners) {
        this.bundles = bundles;
        this.deployments = deployments;
        this.grants = grants;
        this.policies = policies;
        this.owners = owners;
    }

    /** Candidate discovery is bounded and grants no authority by itself. */
    public List<JdbcRuleDeployments.Deployment> candidates(int limit) { return candidates(limit, 0); }

    public List<JdbcRuleDeployments.Deployment> candidates(int limit, int offset) {
        return deployments.executablePage(limit, offset);
    }

    public Inspection inspect(JdbcRuleDeployments.Deployment candidate) {
        if (candidate == null) throw new VclException("DEPLOYMENT_UNAVAILABLE", "");
        var active = current(candidate);
        var bundle = bundles.find(active.account(), active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var graph = new VclParser().readJson(bundle.payload().getBytes(StandardCharsets.UTF_8)).at("/compilation/graph");
        if (!graph.isArray() || graph.isEmpty()) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        grants.read(active.account(), active.epoch());
        var sources = new LinkedHashMap<UUID, VclSourceCaptureDescription>();
        var datasets = new LinkedHashMap<UUID, VclDatasetDescription>();
        UUID policy = null;
        for (var node : graph) {
            var config = node.path("config");
            switch (node.path("method").asText()) {
                case "read_stream" -> {
                    UUID id = uuid(config.path("bindingId").textValue());
                    long revision = config.path("schemaRevision").longValue();
                    var value = owners.capture(active.account(), active.epoch(), id, revision);
                    var previous = sources.putIfAbsent(id, value);
                    if (previous != null && !previous.equals(value)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
                }
                case "lookup_asof" -> {
                    UUID id = uuid(config.at("/target/datasetId").textValue());
                    long revision = config.at("/target/schemaRevision").longValue();
                    var value = owners.datasetDescription(active.account(), active.epoch(), id, revision);
                    var previous = datasets.putIfAbsent(id, value);
                    if (previous != null && !previous.equals(value)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
                }
                case "emit_alert" -> {
                    UUID id = uuid(config.path("evidencePolicyId").textValue());
                    if (policy != null && !policy.equals(id)) throw new VclException("WORKER_POLICY_NOT_PINNED", "");
                    policy = id;
                }
                default -> { }
            }
        }
        if (sources.isEmpty() || sources.size() > 16 || datasets.size() > 16 || policy == null) {
            throw new VclException("OWNER_TARGET_LIMIT", "");
        }
        policies.read(active.account(), active.epoch(), policy);
        grants.read(active.account(), active.epoch());
        current(active);
        boolean ready = sources.values().stream().allMatch(value -> value.capture().ruleExecutionReady());
        return new Inspection(active, Map.copyOf(sources), Map.copyOf(datasets), ready);
    }

    private JdbcRuleDeployments.Deployment current(JdbcRuleDeployments.Deployment expected) {
        var current = deployments.find(expected.account(), expected.epoch())
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!current.acceptsExistingWork() || !expected.equals(current)) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        return current;
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) { throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", ""); }
    }
}
