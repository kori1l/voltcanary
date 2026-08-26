package com.voltcanary.ingestion.adapter.mqtt;

public record MqttAdapterRequest(String topic, byte[] payload) {
}
