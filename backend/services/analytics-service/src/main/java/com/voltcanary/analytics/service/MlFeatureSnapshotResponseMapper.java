package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import org.springframework.stereotype.Component;

@Component
public class MlFeatureSnapshotResponseMapper {

    public MlFeatureSnapshotResponse response(MlFeatureSnapshot snapshot) {
        return new MlFeatureSnapshotResponse(
                snapshot.getId(),
                snapshot.getAccountId(),
                snapshot.getDatasetVersionId(),
                snapshot.getFeatureSetVersion(),
                snapshot.getCodeConfigVersion(),
                snapshot.getFeatureSchemaHash(),
                snapshot.getFeatureDataHash(),
                snapshot.getFeatureConfigHash(),
                snapshot.getRowCount(),
                snapshot.getRejectedRowCount(),
                snapshot.getArtifactUri(),
                snapshot.getImmutableRowReference(),
                snapshot.getModelFamily(),
                snapshot.getHorizonDays(),
                snapshot.getFeatureTemplateId(),
                snapshot.getFeatureTemplateVersion(),
                snapshot.getLifecycleStatus(),
                snapshot.getCreatedAt());
    }
}
