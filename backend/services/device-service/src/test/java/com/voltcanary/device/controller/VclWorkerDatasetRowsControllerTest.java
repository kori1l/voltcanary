package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.device.config.VclWorkerIdentityProperties;
import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.configurable.application.NativeRowWorkflows;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclDocument;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VclWorkerDatasetRowsControllerTest {
    @Test void exactSignedManifestRenewsDatasetAndRejectsChangedRequest() {
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var signer = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var dataset = UUID.randomUUID();
        var field = UUID.randomUUID();
        var payload = new ObjectMapper().createObjectNode();
        payload.putObject("document").put("fixture", "query");
        payload.put("intervalFieldId", field.toString());
        payload.putArray("keyFieldIds").add(field.toString());
        byte[] body = VclCanonical.encode(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var requests = mock(NativeRowsController.class);
        var rows = mock(NativeRowWorkflows.class);
        var queries = mock(NativeRowQueries.class);
        var descriptions = mock(DatasetDescriptions.class);
        var document = mock(VclDocument.class);
        var spec = new ObjectMapper().createObjectNode();
        spec.putObject("target").put("schemaRevision", 1);
        when(document.spec()).thenReturn(spec);
        when(requests.queryDocument(eq(dataset), any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(document);
        when(rows.account(argThat(value -> value != null && actor.equals(value.id())))).thenReturn(account);
        var description = mock(VclDatasetDescription.class);
        when(description.schemaRevision()).thenReturn(1L);
        when(description.schemaDigest()).thenReturn("a".repeat(64));
        when(description.accessScopeDigest()).thenReturn("b".repeat(64));
        when(description.generation()).thenReturn(UUID.randomUUID());
        when(descriptions.describeForAccount(actor, account, dataset, 1)).thenReturn(description);
        var result = mock(NativeQueryPage.ReadManifest.class);
        when(queries.inspectAsOf(eq(account), eq(actor), eq(document), any())).thenReturn(result);
        var controller = new VclWorkerDatasetRowsController(
                new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1"),
                requests, rows, queries, descriptions);
        var path = "/internal/vcl/datasets/" + dataset + "/query-asof-manifest";
        var token = signer.sign(new VclWorkerAssertion.Scope(actor, account, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), dataset, "DATASET_ASOF_MANIFEST",
                VclWorkerAssertion.requestDigest("POST", path, body)), 30);
        assertSame(result, controller.manifest(token, dataset, body));
        verify(descriptions, times(2)).describeForAccount(actor, account, dataset, 1);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.manifest(token, dataset, new byte[]{'{', '}'}));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.manifest(token, UUID.randomUUID(), body));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.asOf(token, dataset, body));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.split(token, dataset, body));
        verify(queries).inspectAsOf(eq(account), eq(actor), eq(document), any());
        verifyNoMoreInteractions(queries);
        var changed = mock(VclDatasetDescription.class);
        when(changed.schemaRevision()).thenReturn(1L);
        when(changed.schemaDigest()).thenReturn("a".repeat(64));
        when(changed.accessScopeDigest()).thenReturn("b".repeat(64));
        when(changed.generation()).thenReturn(UUID.randomUUID());
        when(descriptions.describeForAccount(actor, account, dataset, 1)).thenReturn(description, changed);
        assertEquals("METADATA_SNAPSHOT_CHANGED", assertThrows(com.voltcanary.vcl.VclException.class,
                () -> controller.manifest(token, dataset, body)).code());
    }
}
