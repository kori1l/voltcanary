package com.voltcanary.simulator.dto;

import com.voltcanary.simulator.yaml.ValidationError;
import java.util.List;

public record ValidateConfigResponse(boolean valid, List<ValidationError> errors) {
}
