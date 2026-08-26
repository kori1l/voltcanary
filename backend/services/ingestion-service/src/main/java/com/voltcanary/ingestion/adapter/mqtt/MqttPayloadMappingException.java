package com.voltcanary.ingestion.adapter.mqtt;

public class MqttPayloadMappingException extends IllegalArgumentException {

    public MqttPayloadMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
