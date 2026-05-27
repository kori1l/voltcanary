package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotNull;

public record ExecutePlanRequest(@NotNull Boolean confirmed) {
}
