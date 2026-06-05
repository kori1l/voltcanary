package com.voltcanary.device.shared;

import com.voltcanary.device.domain.Device;

public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
