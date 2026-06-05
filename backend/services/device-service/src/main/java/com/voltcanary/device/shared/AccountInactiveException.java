package com.voltcanary.device.shared;

import com.voltcanary.device.domain.Device;

public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException(String message) {
        super(message);
    }
}
