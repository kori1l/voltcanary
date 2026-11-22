package com.voltcanary.device.dto;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import java.util.List;
import java.util.UUID;

public final class ProcessingBundleViews {

    private ProcessingBundleViews() {}

    public record Entry(long cursor, SourceProcessingConfigEvent bundle) {}

    public record RuntimeTarget(UUID dataSourceId, UUID variableId) {}

    public record RuntimeRequest(UUID deviceId, List<RuntimeTarget> targets) {}
}
