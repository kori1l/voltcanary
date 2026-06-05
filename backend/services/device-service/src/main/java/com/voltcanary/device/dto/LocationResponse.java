package com.voltcanary.device.dto;

import com.voltcanary.device.domain.Device;
import java.util.List;
import java.util.UUID;


public record LocationResponse(
        UUID id,
        String name,
        String description,
        UUID parentId,
        String building,
        String floor,
        String room,
        String zone,
        List<String> tags) {
}
