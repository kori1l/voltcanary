package com.voltcanary.ingestion.adapter.modbus;

import java.util.List;

interface ModbusTcpClientOperations {

    List<ModbusReadResult> read(ModbusTcpAdapterConfig config);
}
