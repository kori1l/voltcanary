package com.voltcanary.ingestion.adapter.opcua;

public interface OpcUaValueChangeHandler {

    void onValue(OpcUaNodeValue value);

    void onFailure(RuntimeException exception);
}
