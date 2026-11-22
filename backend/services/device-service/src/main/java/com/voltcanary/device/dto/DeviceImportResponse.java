package com.voltcanary.device.dto;

import java.util.List;

public record DeviceImportResponse(int importedCount, List<String> errors) {}
