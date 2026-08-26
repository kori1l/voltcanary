package com.voltcanary.ingestion.adapter.opcua;

public interface OpcUaClientOperations {

    OpcUaSubscriptionHandle subscribe(OpcUaAdapterConfig config, OpcUaValueChangeHandler handler);
}
