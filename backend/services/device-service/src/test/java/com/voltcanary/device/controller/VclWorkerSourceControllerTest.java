package com.voltcanary.device.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.device.config.VclWorkerIdentityProperties;
import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.configurable.application.SourceCaptureDescriptions;
import com.voltcanary.device.configurable.application.SourceObservationReads;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VclWorkerSourceControllerTest {

    @Test
    void signedExactReadRenewsLocalOwnerAndRejectsWrongScope() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var signing = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var binding = UUID.randomUUID();
        var scope = new VclWorkerAssertion.Scope(
            actor,
            account,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            binding,
            "SOURCE_CAPTURE",
            VclWorkerAssertion.requestDigest(
                "GET",
                "/internal/vcl/source-bindings/" + binding + "/capture?schemaRevision=1",
                new byte[0]
            )
        );
        var token = signing.sign(scope, 30);
        var owner = mock(SourceCaptureDescriptions.class);
        var response = mock(VclSourceCaptureDescription.class);
        var source = mock(VclSourceBindingDescription.class);
        when(response.source()).thenReturn(source);
        when(source.accountId()).thenReturn(account);
        when(source.actorId()).thenReturn(actor);
        when(
            owner.describe(argThat(value -> actor.equals(value.id())), eq(binding), eq(1L))
        ).thenReturn(response);
        var controller = new VclWorkerSourceController(
            owner,
            mock(SourceObservationReads.class),
            mock(DatasetDescriptions.class),
            new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1")
        );
        assertSame(response, controller.capture(token, binding, 1));
        verify(owner).describe(argThat(value -> actor.equals(value.id())), eq(binding), eq(1L));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.capture(token, binding, 2)
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.capture(token, UUID.randomUUID(), 1)
        );
        when(source.accountId()).thenReturn(UUID.randomUUID());
        assertEquals(
            "OWNER_SNAPSHOT_INVALID",
            assertThrows(VclException.class, () -> controller.capture(token, binding, 1)).code()
        );
    }

    @Test
    void unconfiguredMachineKeyAndMissingAssertionFailClosed() {
        var owner = mock(SourceCaptureDescriptions.class);
        var controller = new VclWorkerSourceController(
            owner,
            mock(SourceObservationReads.class),
            mock(DatasetDescriptions.class),
            new VclWorkerIdentityProperties("", "worker-1")
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.capture(null, UUID.randomUUID(), 1)
        );
        verifyNoInteractions(owner);
    }

    @Test
    void observationAndCoverageReadsBindExactBodyAndFreshOwner() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var signer = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var binding = UUID.randomUUID();
        var pin = new VclObservationReadiness.Pin(
            binding,
            account,
            UUID.randomUUID(),
            "source",
            1,
            UUID.randomUUID(),
            "a".repeat(64)
        );
        var request = new VclObservationRead(pin, UUID.randomUUID(), "0", "1", 1);
        byte[] body = request.bytes();
        var owner = mock(SourceObservationReads.class);
        var page = mock(VclObservationPage.class);
        when(
            owner.read(argThat(value -> actor.equals(value.id())), eq(binding), eq(request))
        ).thenReturn(page);
        var controller = new VclWorkerSourceController(
            mock(SourceCaptureDescriptions.class),
            owner,
            mock(DatasetDescriptions.class),
            new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1")
        );
        var path = "/internal/vcl/source-bindings/" + binding + "/observations";
        var token = signer.sign(
            new VclWorkerAssertion.Scope(
                actor,
                account,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                binding,
                "SOURCE_OBSERVATIONS",
                VclWorkerAssertion.requestDigest("POST", path, body)
            ),
            30
        );
        assertSame(page, controller.observations(token, binding, body));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.observations(token, binding, new byte[] { '{', '}' })
        );
        var proof = UUID.randomUUID();
        byte[] pinBody = com.voltcanary.vcl.VclCanonical.encode(
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(pin)
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var coverage = mock(VclSourceCoverage.class);
        when(
            owner.coverage(
                argThat(value -> actor.equals(value.id())),
                eq(binding),
                eq(pin),
                eq(proof)
            )
        ).thenReturn(coverage);
        var coveragePath = "/internal/vcl/source-bindings/" + binding + "/coverage/" + proof;
        var coverageToken = signer.sign(
            new VclWorkerAssertion.Scope(
                actor,
                account,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                binding,
                "SOURCE_COVERAGE",
                VclWorkerAssertion.requestDigest("POST", coveragePath, pinBody)
            ),
            30
        );
        assertSame(coverage, controller.coverage(coverageToken, binding, proof, pinBody));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.coverage(coverageToken, binding, UUID.randomUUID(), pinBody)
        );
    }

    @Test
    void datasetDescriptionRequiresScopedMachineAssertionAndAccount() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var signer = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var dataset = UUID.randomUUID();
        var owner = mock(DatasetDescriptions.class);
        var response = mock(com.voltcanary.vcl.VclDatasetDescription.class);
        when(owner.describeForAccount(actor, account, dataset, 1L)).thenReturn(response);
        var controller = new VclWorkerSourceController(
            mock(SourceCaptureDescriptions.class),
            mock(SourceObservationReads.class),
            owner,
            new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1")
        );
        var path = "/internal/vcl/datasets/" + dataset + "?schemaRevision=1";
        var token = signer.sign(
            new VclWorkerAssertion.Scope(
                actor,
                account,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                dataset,
                "DATASET_DESCRIPTION",
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            ),
            30
        );
        assertSame(response, controller.dataset(token, dataset, 1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.dataset(token, dataset, 2)
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.dataset(token, UUID.randomUUID(), 1)
        );
        verify(owner).describeForAccount(actor, account, dataset, 1L);
    }
}
