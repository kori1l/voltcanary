package com.voltcanary.ingestion.adapter.modbus;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.exceptions.ModbusTimeoutException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class DigitalPetriModbusTcpClientOperations implements ModbusTcpClientOperations {

    @Override
    public List<ModbusReadResult> read(ModbusTcpAdapterConfig config) {
        NettyTcpClientTransport transport = NettyTcpClientTransport.create(builder -> builder
                .setHostname(config.host())
                .setPort(config.port())
                .setConnectTimeout(config.timeout()));
        ModbusTcpClient client = ModbusTcpClient.create(transport, builder -> builder.setRequestTimeout(config.timeout()));
        try (var ignored = client.open()) {
            List<ModbusReadResult> results = new ArrayList<>();
            for (ModbusReadRequest request : config.readRequests()) {
                results.add(read(client, config.unitId(), request));
            }
            return List.copyOf(results);
        } catch (ModbusTimeoutException exception) {
            throw new IllegalStateException("Modbus TCP operation timed out", exception);
        } catch (ModbusResponseException exception) {
            throw new IllegalStateException("Modbus TCP endpoint returned an error response", exception);
        } catch (ModbusExecutionException exception) {
            throw new IllegalStateException("Modbus TCP connection or operation failed", exception);
        }
    }

    private ModbusReadResult read(ModbusTcpClient client, int unitId, ModbusReadRequest request)
            throws ModbusExecutionException, ModbusResponseException, ModbusTimeoutException {
        int[] registers = switch (request.function()) {
            case HOLDING_REGISTER -> toRegisters(client.readHoldingRegisters(
                    unitId,
                    new ReadHoldingRegistersRequest(request.address(), request.quantity())).registers());
            case INPUT_REGISTER -> toRegisters(client.readInputRegisters(
                    unitId,
                    new ReadInputRegistersRequest(request.address(), request.quantity())).registers());
        };
        return new ModbusReadResult(request, registers);
    }

    private int[] toRegisters(byte[] bytes) {
        if (bytes.length % 2 != 0) {
            throw new IllegalStateException("Modbus TCP register response has an odd byte count");
        }
        int[] registers = new int[bytes.length / 2];
        for (int index = 0; index < registers.length; index++) {
            registers[index] = Byte.toUnsignedInt(bytes[index * 2]) << 8 | Byte.toUnsignedInt(bytes[index * 2 + 1]);
        }
        return registers;
    }
}
