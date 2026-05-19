package com.voltcanary.simulator.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateConfigRequest(@NotBlank String yamlConfig) {
}
