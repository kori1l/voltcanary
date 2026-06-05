package com.voltcanary.device.dto;

import java.util.List;import com.voltcanary.device.domain.Device;


public record DeviceImportResponse(int importedCount, List<String> errors) {
}
