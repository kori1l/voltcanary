package com.voltcanary.ingestion.adapter.modbus;

record ModbusReadResult(ModbusReadRequest request, int[] registers) {
}
