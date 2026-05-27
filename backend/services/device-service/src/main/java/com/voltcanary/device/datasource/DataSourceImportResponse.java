package com.voltcanary.device.datasource;

import java.util.List;

public record DataSourceImportResponse(int importedCount, List<String> errors) {
}
