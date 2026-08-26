package com.voltcanary.ingestion.adapter.opcua;

import java.util.Locale;

class OpcUaValueMapper {

    Object map(OpcUaNodeMapping mapping, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("OPC UA node value for " + mapping.metric() + " must not be null");
        }
        Object unwrapped = unwrap(value);
        if (unwrapped instanceof Number number) {
            double scaled = number.doubleValue() * mapping.scale() + mapping.offset();
            if (isIntegerLike(mapping.dataType())) {
                return Math.round(scaled);
            }
            return scaled;
        }
        if (unwrapped instanceof Boolean || unwrapped instanceof String) {
            return unwrapped;
        }
        throw new IllegalArgumentException("Unsupported OPC UA value type for metric " + mapping.metric());
    }

    private Object unwrap(Object value) {
        try {
            Object variantValue = value.getClass().getMethod("getValue").invoke(value);
            return variantValue == null ? value : variantValue;
        } catch (ReflectiveOperationException ignored) {
            return value;
        }
    }

    private boolean isIntegerLike(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String normalized = dataType.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("INT") || normalized.startsWith("UINT");
    }
}
