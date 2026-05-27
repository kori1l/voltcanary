package com.voltcanary.device.device;

import java.util.List;

public record DeviceImportResponse(int importedCount, List<String> errors) {
}
