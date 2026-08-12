package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.controller.MlDatasetController;
import com.voltcanary.analytics.controller.MlLabelController;
import com.voltcanary.analytics.controller.MlModelRegistryController;
import com.voltcanary.analytics.controller.MlTrainingController;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlDatasetService;
import com.voltcanary.analytics.service.MlExternalDatasetImportService;
import com.voltcanary.analytics.service.MlFeaturePipelineService;
import com.voltcanary.analytics.service.MlHorizonLabelGenerationService;
import com.voltcanary.analytics.service.MlLabelService;
import com.voltcanary.analytics.service.MlModelRegistryService;
import com.voltcanary.analytics.service.MlTrainingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MlLifecycleControllerAuthorizationTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final MlLifecycleActionRequest REQUEST = new MlLifecycleActionRequest("cleanup", false);

    @Test
    void datasetLifecycleWritesRejectViewerRole() {
        MlDatasetController controller = new MlDatasetController(
                mock(MlDatasetService.class),
                mock(MlExternalDatasetImportService.class),
                mock(MlFeaturePipelineService.class),
                mock(MlHorizonLabelGenerationService.class),
                mock(AnalyticsAccessService.class),
                new ObjectMapper());

        assertForbidden(() -> controller.deleteDatasetVersion(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
    }

    @Test
    void featureSnapshotLifecycleWritesRejectViewerRole() {
        MlDatasetController controller = new MlDatasetController(
                mock(MlDatasetService.class),
                mock(MlExternalDatasetImportService.class),
                mock(MlFeaturePipelineService.class),
                mock(MlHorizonLabelGenerationService.class),
                mock(AnalyticsAccessService.class),
                new ObjectMapper());

        assertForbidden(() -> controller.deleteFeatureSnapshot(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
    }

    @Test
    void datasetPreviewRequiresReadAccessForSelectedDevices() {
        AnalyticsAccessService accessService = mock(AnalyticsAccessService.class);
        MlDatasetController controller = new MlDatasetController(
                mock(MlDatasetService.class),
                mock(MlExternalDatasetImportService.class),
                mock(MlFeaturePipelineService.class),
                mock(MlHorizonLabelGenerationService.class),
                accessService,
                new ObjectMapper());

        controller.previewInternalTelemetryDataset(
                ACCOUNT_ID,
                USER_ID.toString(),
                "VIEWER",
                "corr",
                new MlDatasetWorkbenchPreviewRequest(
                        null,
                        List.of(TARGET_ID),
                        null,
                        null,
                        null,
                        List.of("activePowerW"),
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-02T00:00:00Z"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        24,
                        21600L,
                        1));

        verify(accessService).requireReadAccess(TARGET_ID, USER_ID.toString(), "VIEWER", "corr");
    }

    @Test
    void trainingLifecycleWritesRejectViewerRole() {
        MlTrainingController controller = new MlTrainingController(
                mock(MlTrainingService.class),
                mock(AnalyticsAccessService.class));

        assertForbidden(() -> controller.delete(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
    }

    @Test
    void modelAndPolicyLifecycleWritesRejectViewerRole() {
        MlModelRegistryController controller = new MlModelRegistryController(
                mock(MlModelRegistryService.class),
                mock(AnalyticsAccessService.class));

        assertForbidden(() -> controller.deleteModel(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
        assertForbidden(() -> controller.deletePolicy(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
    }

    @Test
    void labelLifecycleWritesRejectViewerRole() {
        MlLabelController controller = new MlLabelController(
                mock(MlLabelService.class),
                mock(AnalyticsAccessService.class));

        assertForbidden(() -> controller.delete(TARGET_ID, ACCOUNT_ID, USER_ID.toString(), "VIEWER", "corr", REQUEST));
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
