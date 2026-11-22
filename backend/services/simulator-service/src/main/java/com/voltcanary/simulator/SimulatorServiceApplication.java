package com.voltcanary.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Import(
    com.voltcanary.common.web.UserIdentityConfiguration.class
)
@SpringBootApplication
@EnableScheduling
public class SimulatorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorServiceApplication.class, args);
    }
}
