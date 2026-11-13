package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owner-only provisioning port; publication requires the resource owner's reviewed command. */
public interface NativeDatasetCatalog {
    void register(UUID accountId, UUID actorId, UUID generation, UUID recordIdFieldId, NativeSchema schema);
    NativeDataset provision(UUID accountId, UUID datasetId);
    Optional<NativeDataset> ready(UUID accountId, UUID datasetId, long schemaRevision);
    List<NativeDataset> list(UUID accountId, int limit);
}
