package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.service.MlDatasetResponseMapper;
import com.voltcanary.analytics.service.MlModelRegistryResponseMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlLineageReadMetadataTest {
    @Test
    void serializesPersistedOwnerAndTrainingLineageWithoutInferringMissingValues() {
        ObjectMapper json = new ObjectMapper();
        UUID owner = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setAccountId(owner);
        var summary = new MlDatasetResponseMapper(json).summary(dataset, version);
        assertThat(json.valueToTree(summary).get("accountId").asText()).isEqualTo(owner.toString());

        MlModelVersion model = new MlModelVersion();
        model.setModelKey("lineage-test");
        model.setTrainingRunId(run);
        var mapper = new MlModelRegistryResponseMapper();
        assertThat(json.valueToTree(mapper.modelResponse(model)).get("trainingRunId").asText()).isEqualTo(run.toString());
        model.setTrainingRunId(null);
        assertThat(json.valueToTree(mapper.modelResponse(model)).get("trainingRunId").isNull()).isTrue();
    }
}
