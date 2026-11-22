package com.voltcanary.device.service;

import com.voltcanary.device.repository.DeviceTypeRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTypeCatalog {

    private final DeviceTypeRepository types;

    @Transactional(readOnly = true)
    public List<Map<String, String>> list() {
        return types
            .findAll()
            .stream()
            .map(type -> Map.of("code", type.getCode(), "displayName", type.getDisplayName()))
            .toList();
    }
}
