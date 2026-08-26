package com.voltcanary.ingestion.adapter.modbus;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ModbusRegisterValueMapper {

    Map<String, Object> map(List<ModbusRegisterMapping> mappings, List<ModbusReadResult> results) {
        Map<ModbusReadRequest, int[]> byRequest = new LinkedHashMap<>();
        for (ModbusReadResult result : results) {
            byRequest.put(result.request(), result.registers());
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (ModbusRegisterMapping mapping : mappings) {
            ModbusReadRequest request = new ModbusReadRequest(
                    mapping.function(),
                    mapping.address(),
                    mapping.dataType().registerCount());
            int[] registers = byRequest.get(request);
            if (registers == null) {
                throw new IllegalStateException("Missing Modbus TCP read result for metric " + mapping.metric());
            }
            metrics.put(mapping.metric(), decode(mapping, registers));
        }
        return Map.copyOf(metrics);
    }

    private Object decode(ModbusRegisterMapping mapping, int[] registers) {
        if (registers.length < mapping.dataType().registerCount()) {
            throw new IllegalStateException("Modbus TCP read returned too few registers for metric " + mapping.metric());
        }
        return switch (mapping.dataType()) {
            case INT16 -> scale((short) register(registers[0]), mapping);
            case UINT16 -> scale(register(registers[0]), mapping);
            case INT32 -> scale((int) unsigned32(registers, mapping), mapping);
            case UINT32 -> scale(unsigned32(registers, mapping), mapping);
            case FLOAT32 -> scale(Float.intBitsToFloat((int) unsigned32(registers, mapping)), mapping);
        };
    }

    private Number scale(double value, ModbusRegisterMapping mapping) {
        double scaled = value * mapping.scale() + mapping.offset();
        if (Math.rint(scaled) == scaled && scaled <= Long.MAX_VALUE && scaled >= Long.MIN_VALUE) {
            return (long) scaled;
        }
        return scaled;
    }

    private long unsigned32(int[] registers, ModbusRegisterMapping mapping) {
        int first = register(registers[0]);
        int second = register(registers[1]);
        if (mapping.wordOrder() == ModbusWordOrder.LITTLE_ENDIAN) {
            int previousFirst = first;
            first = second;
            second = previousFirst;
        }
        byte[] bytes = new byte[] {
                highByte(first),
                lowByte(first),
                highByte(second),
                lowByte(second)
        };
        if (mapping.byteOrder() == ModbusByteOrder.LITTLE_ENDIAN) {
            bytes = new byte[] {
                    bytes[1],
                    bytes[0],
                    bytes[3],
                    bytes[2]
            };
        }
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
    }

    private int register(int value) {
        return value & 0xFFFF;
    }

    private byte highByte(int register) {
        return (byte) ((register >>> 8) & 0xFF);
    }

    private byte lowByte(int register) {
        return (byte) (register & 0xFF);
    }
}
