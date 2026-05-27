package com.voltcanary.device.location;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record LocationRequest(
        UUID parentId,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        @Size(max = 255) String building,
        @Size(max = 100) String floor,
        @Size(max = 255) String room,
        @Size(max = 255) String zone,
        List<@Size(max = 100) String> tags) {
}
