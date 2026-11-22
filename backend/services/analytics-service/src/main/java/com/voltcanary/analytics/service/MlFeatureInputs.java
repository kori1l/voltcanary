package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.domain.MlLabel;
import java.util.List;

public record MlFeatureInputs(
    MlDatasetVersion version,
    List<MlCanonicalMetricRow> internalRows,
    List<MlImportedMetricRow> importedRows,
    List<MlLabel> labels
) {
    public MlFeatureInputs {
        internalRows = List.copyOf(internalRows);
        importedRows = List.copyOf(importedRows);
        labels = List.copyOf(labels);
    }
}
