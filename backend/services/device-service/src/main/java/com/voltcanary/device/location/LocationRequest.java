package com.voltcanary.device.location;

import jakarta.validation.constraints.NotBlank;

public record LocationRequest(@NotBlank String name, String description) {
}
