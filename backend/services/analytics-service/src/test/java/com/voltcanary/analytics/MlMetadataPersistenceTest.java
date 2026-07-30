package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class MlMetadataPersistenceTest {

    @Autowired
    private MlDatasetRepository datasetRepository;
    @Autowired
    private MlTrainingRunRepository trainingRunRepository;
    @Autowired
    private MlModelVersionRepository modelVersionRepository;
    @Autowired
    private MlDeploymentPolicyRepository deploymentPolicyRepository;
    @Autowired
    private MlFeatureSnapshotRepository featureSnapshotRepository;

    @Test
    void repositoriesLoadWithProductionMlMetadataMappings() {
        assertThat(datasetRepository).isNotNull();
        assertThat(trainingRunRepository).isNotNull();
        assertThat(modelVersionRepository).isNotNull();
        assertThat(deploymentPolicyRepository).isNotNull();
        assertThat(featureSnapshotRepository).isNotNull();
    }

    @Test
    void contractEnumsExposeOpenSpecValues() {
        assertThat(MlDatasetStatus.values()).extracting(Enum::name)
                .containsExactly("DRAFT", "VALIDATING", "READY", "FAILED", "ARCHIVED");
        assertThat(MlLabelType.values()).extracting(Enum::name)
                .containsExactly("NORMAL", "ANOMALY", "DEGRADATION", "MAINTENANCE", "UNKNOWN");
        assertThat(MlLabelSource.values()).extracting(Enum::name)
                .containsExactly("MANUAL", "ALERT_OUTCOME", "MAINTENANCE_OUTCOME", "IMPORTED");
        assertThat(MlTrainingSplitStrategy.values()).extracting(Enum::name)
                .containsExactly("TIME_BASED", "DEVICE_BASED", "RANDOM");
        assertThat(MlModelFamily.values()).extracting(Enum::name)
                .containsExactly("ANOMALY_DETECTION", "DEGRADATION_SCORING", "FORECASTING");
        assertThat(MlTrainingRunStatus.values()).extracting(Enum::name)
                .containsExactly("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED");
        assertThat(MlModelStatus.values()).extracting(Enum::name)
                .containsExactly("CANDIDATE", "APPROVED", "ACTIVE", "REJECTED", "ARCHIVED");
        assertThat(MlDeploymentMode.values()).extracting(Enum::name)
                .containsExactly("SHADOW", "CANARY", "ACTIVE", "DISABLED");
        assertThat(MlDeploymentScope.values()).extracting(Enum::name)
                .containsExactly("SYSTEM", "ACCOUNT", "DEVICE_TYPE", "DEVICE");
    }
}
