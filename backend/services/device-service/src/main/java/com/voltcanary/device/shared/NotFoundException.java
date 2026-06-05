package com.voltcanary.device.shared;

import com.voltcanary.device.domain.Device;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
