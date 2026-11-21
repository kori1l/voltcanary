package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.device.config.VclWorkerIdentityProperties;
import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.configurable.application.NativeReviewRequests;
import com.voltcanary.device.configurable.application.NativeRowWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Machine-only reads of an existing actor-owned sealed native snapshot. */
@RestController
public final class VclWorkerDatasetRowsController {
    private final VclWorkerAssertion verifier;
    private final NativeRowsController requests;
    private final NativeRowWorkflows rows;
    private final NativeRowQueries queries;
    private final DatasetDescriptions datasets;

    public VclWorkerDatasetRowsController(VclWorkerIdentityProperties identity, NativeRowsController requests,
            NativeRowWorkflows rows, NativeRowQueries queries, DatasetDescriptions datasets) {
        verifier = identity.forAudience("device-service");
        this.requests = requests;
        this.rows = rows;
        this.queries = queries;
        this.datasets = datasets;
    }

    @PostMapping("/internal/vcl/datasets/{id}/query-asof-manifest")
    public NativeQueryPage.ReadManifest manifest(
            @RequestHeader(value = VclWorkerSourceController.HEADER, required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        var scope = scope(assertion, id, "DATASET_ASOF_MANIFEST", "query-asof-manifest", body);
        var envelope = NativeReviewRequests.closed(body, Set.of("document", "intervalFieldId", "keyFieldIds"));
        var document = requests.queryDocument(id, envelope.path("document"));
        var before = authorize(scope, id, document.spec().at("/target/schemaRevision").longValue());
        var result = queries.inspectAsOf(scope.account(), scope.actor(), document,
                NativeRowsController.asOfProjection(envelope));
        renew(scope, id, before);
        return result;
    }

    @PostMapping("/internal/vcl/datasets/{id}/query-asof")
    public NativeRowQueries.AsOf asOf(
            @RequestHeader(value = VclWorkerSourceController.HEADER, required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        var scope = scope(assertion, id, "DATASET_ASOF", "query-asof", body);
        var envelope = NativeReviewRequests.closed(body, Set.of("document", "intervalFieldId", "eventTime", "keys"));
        var document = requests.queryDocument(id, envelope.path("document"));
        var before = authorize(scope, id, document.spec().at("/target/schemaRevision").longValue());
        var result = queries.asOf(scope.account(), scope.actor(), document,
                NativeRowsController.asOfField(envelope.path("intervalFieldId")),
                requests.asOfTime(envelope.path("eventTime")), NativeRowsController.asOfKeys(envelope));
        renew(scope, id, before);
        return result;
    }

    @PostMapping("/internal/vcl/datasets/{id}/query-asof-split")
    public NativeRowQueries.AsOfSplit split(
            @RequestHeader(value = VclWorkerSourceController.HEADER, required = false) String assertion,
            @PathVariable UUID id, @RequestBody byte[] body) {
        var scope = scope(assertion, id, "DATASET_ASOF_SPLIT", "query-asof-split", body);
        var envelope = NativeReviewRequests.closed(body, Set.of("document", "intervalFieldId", "start", "end", "keys"));
        var document = requests.queryDocument(id, envelope.path("document"));
        var before = authorize(scope, id, document.spec().at("/target/schemaRevision").longValue());
        var result = queries.splitAsOf(scope.account(), scope.actor(), document,
                NativeRowsController.asOfField(envelope.path("intervalFieldId")),
                requests.asOfTime(envelope.path("start")), requests.asOfTime(envelope.path("end")),
                NativeRowsController.asOfKeys(envelope));
        renew(scope, id, before);
        return result;
    }

    private VclDatasetDescription authorize(VclWorkerAssertion.Scope scope, UUID id, long revision) {
        if (revision < 1 || !scope.account().equals(rows.account(actor(scope)))) {
            throw new VclException("OWNER_ACCESS_REVOKED", "");
        }
        // This machine route reads a sealed snapshot. Its exact old schema and generation
        // are verified by the snapshot owner; current metadata supplies only fresh authority.
        return datasets.describeForAccount(scope.actor(), scope.account(), id, 0);
    }

    private void renew(VclWorkerAssertion.Scope scope, UUID id, VclDatasetDescription original) {
        var current = datasets.describeForAccount(scope.actor(), scope.account(), id, 0);
        if (!scope.account().equals(rows.account(actor(scope)))
                || !original.schemaDigest().equals(current.schemaDigest())
                || !original.accessScopeDigest().equals(current.accessScopeDigest())
                || !original.generation().equals(current.generation())) {
            throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        }
    }

    private VclWorkerAssertion.Scope scope(String assertion, UUID id, String operation, String route, byte[] body) {
        if (verifier == null) throw denied();
        var path = "/internal/vcl/datasets/" + id + "/" + route;
        try {
            return verifier.verify(assertion, operation, id, VclWorkerAssertion.requestDigest("POST", path, body));
        } catch (IllegalArgumentException invalid) { throw denied(); }
    }

    private static AuthenticatedUser actor(VclWorkerAssertion.Scope scope) {
        return new AuthenticatedUser(scope.actor(), "", "VCL_WORKER");
    }

    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
}
