package com.voltcanary.device.controller;

import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.device.config.VclWorkerIdentityProperties;
import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.configurable.application.SourceCaptureDescriptions;
import com.voltcanary.device.configurable.application.SourceObservationReads;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import com.voltcanary.vcl.VclSourceCoverage;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Scoped machine owner reads still renew local account/resource authorization. */
@RestController
public final class VclWorkerSourceController {

    public static final String HEADER = "X-VCL-Worker-Assertion";
    private final SourceCaptureDescriptions captures;
    private final SourceObservationReads observations;
    private final DatasetDescriptions datasets;
    private final VclWorkerAssertion verifier;

    public VclWorkerSourceController(
        SourceCaptureDescriptions captures,
        SourceObservationReads observations,
        DatasetDescriptions datasets,
        VclWorkerIdentityProperties identity
    ) {
        this.captures = captures;
        this.observations = observations;
        this.datasets = datasets;
        this.verifier = identity.forAudience("device-service");
    }

    @GetMapping("/internal/vcl/datasets/{id}")
    public VclDatasetDescription dataset(
        @RequestHeader(value = HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @RequestParam long schemaRevision
    ) {
        var scope = scope(
            assertion,
            "DATASET_DESCRIPTION",
            id,
            "GET",
            "/internal/vcl/datasets/" + id + "?schemaRevision=" + schemaRevision,
            new byte[0]
        );
        return datasets.describeForAccount(scope.actor(), scope.account(), id, schemaRevision);
    }

    @GetMapping("/internal/vcl/source-bindings/{id}/capture")
    public VclSourceCaptureDescription capture(
        @RequestHeader(value = HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @RequestParam long schemaRevision
    ) {
        var path =
            "/internal/vcl/source-bindings/" + id + "/capture?schemaRevision=" + schemaRevision;
        var scope = scope(assertion, "SOURCE_CAPTURE", id, "GET", path, new byte[0]);
        var result = captures.describe(actor(scope), id, schemaRevision);
        if (
            !scope.account().equals(result.source().accountId()) ||
            !scope.actor().equals(result.source().actorId())
        ) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        return result;
    }

    @PostMapping("/internal/vcl/source-bindings/{id}/observations")
    public VclObservationPage observations(
        @RequestHeader(value = HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var scope = scope(
            assertion,
            "SOURCE_OBSERVATIONS",
            id,
            "POST",
            "/internal/vcl/source-bindings/" + id + "/observations",
            body
        );
        var request = VclObservationRead.parse(body);
        if (!scope.account().equals(request.pin().accountId())) throw new VclException(
            "OWNER_SNAPSHOT_INVALID",
            ""
        );
        return observations.read(actor(scope), id, request);
    }

    @PostMapping("/internal/vcl/source-bindings/{id}/coverage/{proof}")
    public VclSourceCoverage coverage(
        @RequestHeader(value = HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @PathVariable UUID proof,
        @RequestBody byte[] body
    ) {
        var scope = scope(
            assertion,
            "SOURCE_COVERAGE",
            id,
            "POST",
            "/internal/vcl/source-bindings/" + id + "/coverage/" + proof,
            body
        );
        var pin = VclObservationReadiness.parsePin(body);
        if (!scope.account().equals(pin.accountId())) throw new VclException(
            "OWNER_SNAPSHOT_INVALID",
            ""
        );
        return observations.coverage(actor(scope), id, pin, proof);
    }

    @PostMapping("/internal/vcl/source-bindings/{id}/coverage-search")
    public com.fasterxml.jackson.databind.JsonNode findCoverage(
        @RequestHeader(value = HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @RequestBody byte[] body
    ) {
        var scope = scope(
            assertion,
            "SOURCE_COVERAGE_SEARCH",
            id,
            "POST",
            "/internal/vcl/source-bindings/" + id + "/coverage-search",
            body
        );
        var request = com.voltcanary.vcl.VclCoverageSearch.parse(body);
        if (!scope.account().equals(request.pin().accountId())) throw new VclException(
            "OWNER_SNAPSHOT_INVALID",
            ""
        );
        UUID proof = observations.findQualifiedCoverage(actor(scope), id, request);
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (proof == null) result.putNull("proofId");
        else result.put("proofId", proof.toString());
        return result;
    }

    private VclWorkerAssertion.Scope scope(
        String assertion,
        String operation,
        UUID id,
        String method,
        String path,
        byte[] body
    ) {
        if (verifier == null) throw denied();
        try {
            return verifier.verify(
                assertion,
                operation,
                id,
                VclWorkerAssertion.requestDigest(method, path, body)
            );
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
    }

    private static AuthenticatedUser actor(VclWorkerAssertion.Scope scope) {
        return new AuthenticatedUser(scope.actor(), "", "VCL_WORKER");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
