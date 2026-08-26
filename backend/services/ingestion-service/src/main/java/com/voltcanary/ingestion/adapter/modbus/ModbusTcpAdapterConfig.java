package com.voltcanary.ingestion.adapter.modbus;

import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;

record ModbusTcpAdapterConfig(
        String host,
        int port,
        int unitId,
        Duration timeout,
        Duration pollingInterval,
        ModbusTimestampPolicy timestampPolicy,
        List<ModbusRegisterMapping> registers) {

    static ModbusTcpAdapterConfig from(SourceRuntimeConfig sourceRuntimeConfig) {
        if (!"v1".equalsIgnoreCase(sourceRuntimeConfig.adapterConfigSchemaVersion())) {
            throw new IllegalArgumentException("Unsupported Modbus TCP adapter config schema version");
        }
        Map<String, Object> adapterConfig = sourceRuntimeConfig.adapterConfig();
        String host = string(adapterConfig, "host", null);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Modbus TCP host is required");
        }
        int port = integer(adapterConfig, "port", 502);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Modbus TCP port must be between 1 and 65535");
        }
        int unitId = integer(adapterConfig, "unitId", 1);
        if (unitId < 0 || unitId > 255) {
            throw new IllegalArgumentException("Modbus TCP unitId must be between 0 and 255");
        }
        Duration timeout = duration(adapterConfig, "timeout", Duration.ofSeconds(5));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Modbus TCP timeout must be positive");
        }
        Duration pollingInterval = duration(adapterConfig, "pollingInterval", null);
        if (pollingInterval == null) {
            pollingInterval = duration(adapterConfig, "pollInterval", null);
        }
        if (pollingInterval == null || pollingInterval.isZero() || pollingInterval.isNegative()) {
            throw new IllegalArgumentException("Modbus TCP polling interval must be positive");
        }
        ModbusTimestampPolicy timestampPolicy = enumValue(
                ModbusTimestampPolicy.class,
                string(adapterConfig, "timestampPolicy", "POLL_TIME"),
                "timestampPolicy");
        return new ModbusTcpAdapterConfig(
                host.trim(),
                port,
                unitId,
                timeout,
                pollingInterval,
                timestampPolicy,
                registers(sourceRuntimeConfig.mapping()));
    }

    List<ModbusReadRequest> readRequests() {
        return registers.stream()
                .map(mapping -> new ModbusReadRequest(mapping.function(), mapping.address(), mapping.dataType().registerCount()))
                .toList();
    }

    private static List<ModbusRegisterMapping> registers(Map<String, Object> mapping) {
        Object value = mapping.get("registers");
        if (!(value instanceof List<?> rawRegisters) || rawRegisters.isEmpty()) {
            throw new IllegalArgumentException("Modbus TCP mapping registers must be a non-empty array");
        }
        List<ModbusRegisterMapping> registers = new ArrayList<>();
        for (Object item : rawRegisters) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Modbus TCP register mapping entries must be objects");
            }
            registers.add(register(raw));
        }
        return List.copyOf(registers);
    }

    private static ModbusRegisterMapping register(Map<?, ?> raw) {
        String metric = requiredString(raw, "metric");
        int address = integer(raw, "address", -1);
        if (address < 0 || address > 65535) {
            throw new IllegalArgumentException("Modbus TCP register address must be between 0 and 65535");
        }
        ModbusRegisterFunction function = enumValue(
                ModbusRegisterFunction.class,
                string(raw, "function", "HOLDING_REGISTER"),
                "function");
        ModbusDataType dataType = enumValue(ModbusDataType.class, string(raw, "dataType", "UINT16"), "dataType");
        return new ModbusRegisterMapping(
                metric,
                function,
                address,
                dataType,
                decimal(raw, "scale", 1.0),
                decimal(raw, "offset", 0.0),
                string(raw, "unit", null),
                enumValue(ModbusByteOrder.class, string(raw, "byteOrder", "BIG_ENDIAN"), "byteOrder"),
                enumValue(ModbusWordOrder.class, string(raw, "wordOrder", "BIG_ENDIAN"), "wordOrder"));
    }

    private static String requiredString(Map<?, ?> config, String key) {
        String value = string(config, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Modbus TCP " + key + " is required");
        }
        return value.trim();
    }

    private static String string(Map<?, ?> config, String key, String fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Modbus TCP " + key + " must be a string");
        }
        return text;
    }

    private static int integer(Map<?, ?> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Modbus TCP " + key + " must be a number");
    }

    private static double decimal(Map<?, ?> config, String key, double fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Modbus TCP " + key + " must be a number");
    }

    private static Duration duration(Map<String, Object> config, String key, Duration fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return Duration.ofMillis(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return DurationStyle.detectAndParse(text.trim());
        }
        throw new IllegalArgumentException("Modbus TCP " + key + " must be a duration");
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Modbus TCP " + field + " must not be blank");
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return Enum.valueOf(type, normalized);
    }
}
