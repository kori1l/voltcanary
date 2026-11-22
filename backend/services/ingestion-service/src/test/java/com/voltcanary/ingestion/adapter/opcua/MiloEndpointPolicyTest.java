package com.voltcanary.ingestion.adapter.opcua;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voltcanary.ingestion.adapter.api.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.junit.jupiter.api.Test;

class MiloEndpointPolicyTest {

    private OpcUaAdapterConfig config() {
        return OpcUaAdapterConfig.from(
            new SourceRuntimeConfig(
                "source",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdapterType.GENERIC_OPC_UA,
                ProtocolType.OPC_UA,
                TransportType.TCP,
                "ACTIVE",
                "v1",
                "v1",
                Map.of("nodes", List.of(Map.of("metric", "power", "nodeId", "ns=2;s=Power"))),
                Map.of(
                    "endpointUrl",
                    "opc.tcp://sensor.example:4840",
                    "securityPolicy",
                    "Basic256Sha256"
                ),
                Map.of(),
                "default",
                1
            )
        );
    }

    @Test
    void disconnectsAnEstablishedClientWhenSubscriptionCreationFails() {
        var client = mock(OpcUaClient.class, RETURNS_DEEP_STUBS);
        when(client.connect()).thenReturn(CompletableFuture.completedFuture(client));
        when(client.disconnect()).thenReturn(CompletableFuture.completedFuture(client));
        when(client.getSubscriptionManager().createSubscription(anyDouble())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("Subscription rejected"))
        );
        var operations = new MiloOpcUaClientOperations((reference, access) ->
            java.util.Optional.empty()
        ) {
            @Override
            OpcUaClient createClient(OpcUaAdapterConfig config, IdentityProvider identity) {
                return client;
            }
        };
        assertThatThrownBy(() -> operations.subscribe(config(), null)).isInstanceOf(
            IllegalStateException.class
        );
        verify(client).disconnect();
    }

    @Test
    void neverDowngradesAnExplicitPolicyToTheFirstAvailableEndpoint() {
        var source = new SourceRuntimeConfig(
            "source",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AdapterType.GENERIC_OPC_UA,
            ProtocolType.OPC_UA,
            TransportType.TCP,
            "ACTIVE",
            "v1",
            "v1",
            Map.of("nodes", List.of(Map.of("metric", "power", "nodeId", "ns=2;s=Power"))),
            Map.of(
                "endpointUrl",
                "opc.tcp://sensor.example:4840",
                "securityPolicy",
                "Basic256Sha256"
            ),
            Map.of(),
            "default",
            1
        );
        var config = OpcUaAdapterConfig.from(source);
        var insecure = mock(EndpointDescription.class);
        when(insecure.getSecurityPolicyUri()).thenReturn(
            "http://opcfoundation.org/UA/SecurityPolicy#None"
        );
        var required = mock(EndpointDescription.class);
        when(required.getSecurityPolicyUri()).thenReturn(
            "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256"
        );
        assertThat(MiloOpcUaClientOperations.selectEndpoint(config, List.of(insecure))).isEmpty();
        assertThat(
            MiloOpcUaClientOperations.selectEndpoint(config, List.of(insecure, required))
        ).contains(required);
        assertThat(MiloOpcUaClientOperations.selectEndpoint(config, List.of())).isEmpty();
    }
}
