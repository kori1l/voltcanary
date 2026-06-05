package com.voltcanary.device.shared;

import com.voltcanary.device.domain.Device;

public record ApiErrorResponse(String code, String message) {
}
