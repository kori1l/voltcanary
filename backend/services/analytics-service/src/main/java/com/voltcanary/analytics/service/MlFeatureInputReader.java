package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.MlFeatureGenerationProperties;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MlFeatureInputReader {

    private final MlDatasetVersionRepository versions;
    private final MlCanonicalMetricRowRepository canonical;
    private final MlImportedFileRepository files;
    private final MlImportedMetricRowRepository imported;
    private final MlLabelRepository labels;
    private final MlFeatureGenerationProperties limits;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MlFeatureInputs capture(UUID account, UUID versionId) {
        var version = versions
            .findById(versionId)
            .filter(found -> account.equals(found.getAccountId()))
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found")
            );
        if (version.getStatus() != MlDatasetStatus.READY) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "ML dataset version must be READY"
            );
        }
        return captureRows(version);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MlFeatureInputs captureRows(MlDatasetVersion version) {
        var capturedLabels = bounded(
            labels.findByAccountIdAndDatasetVersionIdAndDeletedAtIsNull(
                version.getAccountId(),
                version.getId(),
                PageRequest.of(0, limits.getMaxLabels() + 1)
            ),
            limits.getMaxLabels(),
            "labels"
        );
        if (version.getSourceType() == MlDatasetSourceType.INTERNAL_TELEMETRY) {
            var rows = bounded(
                canonical.findByDatasetVersionIdOrderByTimestampAsc(
                    version.getId(),
                    PageRequest.of(0, limits.getMaxInputRows() + 1)
                ),
                limits.getMaxInputRows(),
                "input rows"
            );
            return new MlFeatureInputs(version, rows, List.of(), capturedLabels);
        }
        if (version.getSourceType() != MlDatasetSourceType.EXTERNAL_IMPORT) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported ML feature source type"
            );
        }
        var capturedFiles = bounded(
            files.findByDatasetIdOrderByImportedAtDesc(
                version.getDatasetId(),
                PageRequest.of(0, limits.getMaxImportedFiles() + 1)
            ),
            limits.getMaxImportedFiles(),
            "imported files"
        );
        List<MlImportedMetricRow> rows = new ArrayList<>();
        for (var file : capturedFiles) {
            rows.addAll(
                imported.findByImportedFileIdOrderBySourceRowNumber(
                    file.getId(),
                    PageRequest.of(0, limits.getMaxInputRows() - rows.size() + 1)
                )
            );
            bounded(rows, limits.getMaxInputRows(), "input rows");
        }
        return new MlFeatureInputs(version, List.of(), rows, capturedLabels);
    }

    private <T> List<T> bounded(List<T> rows, int maximum, String input) {
        if (rows.size() > maximum) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Feature generation exceeds configured " +
                    input +
                    " limit; select a smaller dataset"
            );
        }
        return rows;
    }
}
