package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Exact signed as-of reads for graph-declared native lookup projections only. */
@Component
public final class WorkerDatasetAsOfReads {
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final ConfigurableOwnerReads reads;
    private final VclWorkerAssertion signer;
    private final WorkerSourceCaptureReads metadata;
    private final NativeAsOfOwner asOf;

    public WorkerDatasetAsOfReads(DataSource source, RestClient.Builder builder, AnalyticsDownstreamProperties properties,
            VclWorkerIdentityProperties identity, WorkerSourceCaptureReads metadata, NativeAsOfOwner asOf) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        var target = properties.getDeviceService();
        reads = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("device-service");
        this.metadata = metadata;
        this.asOf = asOf;
    }

    public JsonNode inspect(UUID account, UUID epoch, VclDocument query, UUID intervalField,
            List<UUID> keyFields, long deadline) {
        return asOf.inspect(transport(account, epoch), query, intervalField, keyFields, deadline);
    }

    public JsonNode lookup(UUID account, UUID epoch, VclCompilation.Node node, VclDocument query,
            UUID intervalField, Map<UUID, JsonNode> eventFields, Instant eventTime,
            JsonNode expectedManifest, long deadline) {
        return asOf.lookup(transport(account, epoch), node, query, intervalField,
                eventFields, eventTime, expectedManifest, deadline);
    }

    NativeAsOfOwner.Transport transport(UUID account, UUID epoch) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        if (account == null || epoch == null) throw new VclException("WORKER_TARGET_INVALID", "");
        return new NativeAsOfOwner.Transport() {
            public byte[] manifest(UUID dataset, byte[] body, long deadline) {
                return read(account, epoch, dataset, body, deadline, "query-asof-manifest", "DATASET_ASOF_MANIFEST", false);
            }
            public byte[] rows(UUID dataset, byte[] body, long deadline, boolean split) {
                return read(account, epoch, dataset, body, deadline, split ? "query-asof-split" : "query-asof",
                        split ? "DATASET_ASOF_SPLIT" : "DATASET_ASOF", split);
            }
        };
    }

    private byte[] read(UUID account, UUID epoch, UUID dataset, byte[] body, long deadline,
            String route, String operation, boolean split) {
        if (System.nanoTime() - deadline >= 0) throw new VclException("OWNER_SNAPSHOT_EXPIRED", "");
        var active = deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!active.acceptsExistingWork() && !(active.permitsOwnerInspection()
                && operation.equals("DATASET_ASOF_MANIFEST"))) {
            throw new VclException("DEPLOYMENT_INACTIVE", "");
        }
        var bundle = bundles.find(account, active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var request = new VclParser().readJson(body);
        var query = new VclParser().parse(request.path("document").toString());
        if (query.kind() != VclDocument.Kind.QUERY || !dataset.toString().equals(query.spec().at("/target/datasetId").textValue())
                || !"SNAPSHOT".equals(query.spec().at("/read/mode").textValue())) {
            throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
        }
        long revision = query.spec().at("/target/schemaRevision").longValue();
        var compiled = bundle.json()
                .path("compilation");
        if (!declared(compiled, dataset, revision, request, query, route)) {
            throw new VclException("WORKER_DATASET_NOT_PINNED", "");
        }
        VclDatasetDescription before = metadata.datasetDescription(account, epoch, dataset, revision);
        var path = "/internal/vcl/datasets/" + dataset + "/" + route;
        var assertion = signer.sign(new VclWorkerAssertion.Scope(active.actor(), account, active.rule(), epoch,
                active.grant(), dataset, operation, VclWorkerAssertion.requestDigest("POST", path, body)), 20);
        byte[] result;
        try {
            result = operation.equals("DATASET_ASOF_MANIFEST")
                    ? reads.workerDatasetAsOfManifest(assertion, dataset, body, deadline)
                    : reads.workerDatasetAsOf(assertion, dataset, body, deadline, split);
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        var response = new VclParser().readJson(result);
        var manifest = operation.equals("DATASET_ASOF_MANIFEST") ? response : response.path("readManifest");
        if (!dataset.toString().equals(manifest.path("datasetId").textValue())
                || revision != manifest.path("schemaRevision").longValue()
                || !before.schemaDigest().equals(manifest.path("schemaDigest").textValue())
                || !before.accessScopeDigest().equals(manifest.path("accessScopeDigest").textValue())
                || !before.generation().toString().equals(manifest.path("generation").textValue())) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        var after = metadata.datasetDescription(account, epoch, dataset, revision);
        if (!before.schemaDigest().equals(after.schemaDigest())
                || !before.accessScopeDigest().equals(after.accessScopeDigest())
                || !before.generation().equals(after.generation())) {
            throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        }
        if (!active.equals(deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", "")))) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        return result;
    }

    private static boolean declared(JsonNode compilation, UUID dataset, long revision, JsonNode request,
            VclDocument query, String route) {
        var selected = ids(query.spec().path("select"));
        var interval = request.path("intervalFieldId").textValue();
        if (interval == null) return false;
        for (var node : compilation.path("graph")) {
            if (!"lookup_asof".equals(node.path("method").textValue())) continue;
            var config = node.path("config");
            if (!dataset.toString().equals(config.at("/target/datasetId").textValue())
                    || revision != config.at("/target/schemaRevision").longValue()) continue;
            String mode = config.at("/temporal/mode").textValue();
            if ("query-asof".equals(route) && !"EVENT_TIME".equals(mode)
                    || "query-asof-split".equals(route) && !"INTERVAL_SPLIT".equals(mode)) continue;
            var keys = new HashSet<String>();
            config.path("keyPairs").forEach(pair -> keys.add(pair.path("recordFieldId").asText()));
            var providedKeys = new HashSet<String>();
            if (request.has("keyFieldIds")) request.path("keyFieldIds").forEach(id -> providedKeys.add(id.asText()));
            else request.path("keys").forEach(key -> providedKeys.add(key.path("fieldId").asText()));
            var projection = ids(config.path("select"));
            projection.addAll(keys);
            projection.add(interval);
            if (keys.equals(providedKeys) && selected.equals(projection)) return true;
        }
        return false;
    }

    private static Set<String> ids(JsonNode values) {
        var result = new HashSet<String>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
