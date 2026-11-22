package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.dto.MlSettingsResponse;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsResourceContext {

    public record Context(
        List<MaintenanceWindowReadModel> windows,
        String deviceType,
        UUID accountId,
        MlSettingsResponse settings,
        MlResolvedDeploymentPolicyResponse policy
    ) {}

    private final MaintenanceWindowProjectionService maintenance;
    private final DeviceMetadataClient devices;
    private final MlPolicyService settings;
    private final MlModelRegistryService models;

    public Context read(UUID device, Instant from, Instant to) {
        var windows = maintenance.activeOverlapping(device, from, to);
        var metadata = devices.metadata(device);
        String type = metadata == null || metadata.type() == null ? "" : metadata.type().trim();
        var effective = settings.effectiveForAnalytics(
            device,
            windows
                .stream()
                .map(MaintenanceWindowReadModel::getAccountId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(metadata == null ? null : metadata.accountId())
        );
        UUID account =
            effective.accountId() == null && metadata != null
                ? metadata.accountId()
                : effective.accountId();
        var policy =
            account == null
                ? new MlResolvedDeploymentPolicyResponse(
                      null,
                      null,
                      null,
                      MlDeploymentMode.DISABLED,
                      true,
                      "ACCOUNT_UNKNOWN"
                  )
                : models.resolvePolicy(account, device, type);
        return new Context(List.copyOf(windows), type, account, effective, policy);
    }
}
