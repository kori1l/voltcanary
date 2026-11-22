package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database capture and publication surround pure bounded computation. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class MlFeatureSnapshotGenerationService {

    private final MlFeatureInputReader inputs;
    private final MlFeatureGenerationService generation;
    private final MlFeatureSnapshotAssembler assembler;
    private final MlFeatureSnapshotWrites writes;

    public MlFeatureSnapshotResponse generate(UUID accountId, MlFeatureSnapshotRequest request) {
        var captured = inputs.capture(accountId, request.datasetVersionId());
        var config = MlFeatureConfig.from(request);
        var calculated = generation.generateFeatures(captured, config);
        return writes.save(captured, assembler.assemble(captured, config, calculated));
    }
}
