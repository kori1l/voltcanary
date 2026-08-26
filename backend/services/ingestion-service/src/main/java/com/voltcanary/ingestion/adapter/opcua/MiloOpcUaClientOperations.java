package com.voltcanary.ingestion.adapter.opcua;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.voltcanary.ingestion.adapter.http.CredentialProvider;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.springframework.stereotype.Component;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

@Component
class MiloOpcUaClientOperations implements OpcUaClientOperations {

    private final CredentialProvider credentialProvider;

    MiloOpcUaClientOperations(CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    @Override
    public OpcUaSubscriptionHandle subscribe(OpcUaAdapterConfig config, OpcUaValueChangeHandler handler) {
        if (config.endpoint() == null) {
            throw new IllegalArgumentException("OPC UA endpointReference must be resolved before opening a Milo session");
        }
        try {
            IdentityProvider identityProvider = identityProvider(config);
            OpcUaClient client = OpcUaClient.create(
                    config.endpoint().toString(),
                    endpoints -> selectEndpoint(config, endpoints),
                    builder -> builder
                            .setIdentityProvider(identityProvider)
                            .setSessionTimeout(uint(config.sessionTimeout().toMillis()))
                            .setRequestTimeout(uint(config.sessionTimeout().toMillis()))
                            .build());
            client.connect().get(config.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            UaSubscription subscription = client.getSubscriptionManager()
                    .createSubscription(config.publishingInterval().toMillis())
                    .get(config.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            List<MonitoredItemCreateRequest> requests = requests(subscription, config);
            subscription.createMonitoredItems(TimestampsToReturn.Both, requests, (item, index) -> {
                OpcUaNodeMapping mapping = config.nodes().get(index);
                item.setValueConsumer(value -> handleValue(mapping, value, handler));
            }).get(config.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new MiloOpcUaSubscriptionHandle(client, subscription);
        } catch (Exception exception) {
            throw new IllegalStateException("OPC UA subscription failed", exception);
        }
    }

    private java.util.Optional<EndpointDescription> selectEndpoint(
            OpcUaAdapterConfig config,
            List<EndpointDescription> endpoints) {
        return endpoints.stream()
                .filter(endpoint -> matchesSecurityPolicy(config, endpoint))
                .findFirst()
                .or(() -> endpoints.stream().findFirst());
    }

    private boolean matchesSecurityPolicy(OpcUaAdapterConfig config, EndpointDescription endpoint) {
        String securityPolicy = config.securityPolicy();
        if (securityPolicy == null || securityPolicy.isBlank()) {
            return true;
        }
        String uri = endpoint.getSecurityPolicyUri();
        return uri != null && uri.toLowerCase(java.util.Locale.ROOT)
                .endsWith("#" + securityPolicy.toLowerCase(java.util.Locale.ROOT));
    }

    private IdentityProvider identityProvider(OpcUaAdapterConfig config) {
        return switch (config.authenticationMode()) {
            case ANONYMOUS -> AnonymousProvider.INSTANCE;
            case USERNAME -> new UsernameProvider(
                    resolve(config.usernameCredentialRef(), "username"),
                    resolve(config.passwordCredentialRef(), "password"));
            case CERTIFICATE -> throw new IllegalArgumentException(
                    "OPC UA certificate authentication requires deployment certificate material wiring");
        };
    }

    private String resolve(String credentialReference, String name) {
        return credentialProvider.resolve(credentialReference)
                .orElseThrow(() -> new IllegalArgumentException("OPC UA " + name + " credential reference could not be resolved"));
    }

    private List<MonitoredItemCreateRequest> requests(UaSubscription subscription, OpcUaAdapterConfig config) {
        List<MonitoredItemCreateRequest> requests = new ArrayList<>();
        for (OpcUaNodeMapping node : config.nodes()) {
            if (node.nodeId() == null) {
                throw new IllegalArgumentException("OPC UA browsePath must be resolved to nodeId before subscription");
            }
            ReadValueId readValueId = new ReadValueId(
                    NodeId.parse(node.nodeId()),
                    AttributeId.Value.uid(),
                    null,
                    null);
            MonitoringParameters parameters = new MonitoringParameters(
                    subscription.nextClientHandle(),
                    (double) config.samplingInterval().toMillis(),
                    null,
                    uint(config.queueSize()),
                    true);
            requests.add(new MonitoredItemCreateRequest(readValueId, MonitoringMode.Reporting, parameters));
        }
        return List.copyOf(requests);
    }

    private void handleValue(OpcUaNodeMapping mapping, DataValue value, OpcUaValueChangeHandler handler) {
        if (value.getStatusCode() != null && value.getStatusCode().isBad()) {
            handler.onFailure(new IllegalStateException("OPC UA node status is bad for metric " + mapping.metric()));
            return;
        }
        handler.onValue(new OpcUaNodeValue(
                mapping,
                value.getValue(),
                instant(value.getSourceTime()),
                instant(value.getServerTime())));
    }

    private java.time.Instant instant(DateTime dateTime) {
        return dateTime == null || dateTime.isNull() ? null : dateTime.getJavaInstant();
    }

    private record MiloOpcUaSubscriptionHandle(OpcUaClient client, UaSubscription subscription)
            implements OpcUaSubscriptionHandle {

        @Override
        public void close() {
            try {
                client.getSubscriptionManager().deleteSubscription(subscription.getSubscriptionId()).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Disconnect below still closes the local socket even if deleteSubscription fails.
            }
            try {
                client.disconnect().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best-effort close during lease loss and config replacement.
            }
        }
    }
}
