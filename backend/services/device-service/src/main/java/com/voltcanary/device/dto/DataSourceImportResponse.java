package com.voltcanary.device.dto;

import java.util.List;

public record DataSourceImportResponse(int importedCount, List<String> errors) {
}
