package com.voltcanary.ingestion.adapter.modbus;

record ModbusRegisterMapping(
        String metric,
        ModbusRegisterFunction function,
        int address,
        ModbusDataType dataType,
        double scale,
        double offset,
        String unit,
        ModbusByteOrder byteOrder,
        ModbusWordOrder wordOrder) {
}
