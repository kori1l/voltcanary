package com.voltcanary.device;

import com.voltcanary.device.domain.Device;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class DeviceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceServiceApplication.class, args);
    }
}
