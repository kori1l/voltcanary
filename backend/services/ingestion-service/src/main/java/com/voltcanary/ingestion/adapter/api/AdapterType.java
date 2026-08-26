package com.voltcanary.ingestion.adapter.api;

public enum AdapterType {
    GENERIC_HTTP_PUSH,
    GENERIC_MQTT,
    CSV_IMPORT,
    WEBHOOK,
    GENERIC_HTTP_POLLING,
    GENERIC_MODBUS_TCP,
    GENERIC_OPC_UA,
    VENDOR
}
