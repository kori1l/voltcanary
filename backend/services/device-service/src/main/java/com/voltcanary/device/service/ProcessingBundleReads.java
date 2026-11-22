package com.voltcanary.device.service;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.ProcessingBundleViews.Entry;
import com.voltcanary.device.dto.ProcessingBundleViews.RuntimeRequest;
import com.voltcanary.device.dto.ProcessingBundleViews.RuntimeTarget;
import com.voltcanary.device.repository.ProcessingBundleRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessingBundleReads {

    private static final int MAX_TARGETS = 500;
    private final InternalServiceAuth auth;
    private final ProcessingBundleRepository repository;

    public List<Entry> list(String token, long after, int limit, UUID sourceId, Long generation) {
        auth.require(token);
        if (
            after < 0 ||
            limit < 1 ||
            limit > MAX_TARGETS ||
            (generation != null && (sourceId == null || generation < 1))
        ) {
            throw new IllegalArgumentException("Invalid processing bundle cursor/filter");
        }
        return repository.list(after, limit, sourceId, generation);
    }

    public Map<String, Boolean> active(String token, UUID device, UUID source, UUID variable) {
        auth.require(token);
        return Map.of("enabled", repository.active(device, source, variable));
    }

    public List<RuntimeTarget> activeBatch(String token, RuntimeRequest request) {
        auth.require(token);
        if (
            request == null ||
            request.deviceId() == null ||
            request.targets() == null ||
            request.targets().size() > MAX_TARGETS ||
            request
                .targets()
                .stream()
                .anyMatch(
                    target ->
                        target == null ||
                        target.dataSourceId() == null ||
                        target.variableId() == null
                )
        ) {
            throw new IllegalArgumentException("Invalid variable capability batch");
        }
        return request.targets().isEmpty()
            ? List.of()
            : repository.activeBatch(request.deviceId(), request.targets());
    }
}
