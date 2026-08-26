package com.voltcanary.ingestion.adapter.modbus;

enum ModbusDataType {
    INT16(1),
    UINT16(1),
    INT32(2),
    UINT32(2),
    FLOAT32(2);

    private final int registerCount;

    ModbusDataType(int registerCount) {
        this.registerCount = registerCount;
    }

    int registerCount() {
        return registerCount;
    }
}
