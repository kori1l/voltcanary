package com.voltcanary.ingestion.adapter.opcua;

public interface OpcUaSubscriptionHandle extends AutoCloseable {

    @Override
    void close();
}
