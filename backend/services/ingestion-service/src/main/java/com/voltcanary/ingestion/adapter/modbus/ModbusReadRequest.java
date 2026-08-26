package com.voltcanary.ingestion.adapter.modbus;

record ModbusReadRequest(ModbusRegisterFunction function, int address, int quantity) {
}
