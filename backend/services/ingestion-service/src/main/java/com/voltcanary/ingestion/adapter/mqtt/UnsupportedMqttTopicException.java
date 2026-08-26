package com.voltcanary.ingestion.adapter.mqtt;

public class UnsupportedMqttTopicException extends IllegalArgumentException {

    public UnsupportedMqttTopicException(String topic) {
        super("Unsupported MQTT telemetry topic: " + topic);
    }
}
