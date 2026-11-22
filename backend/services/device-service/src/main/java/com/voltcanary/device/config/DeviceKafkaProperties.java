package com.voltcanary.device.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.kafka")
@Getter
@Setter
public class DeviceKafkaProperties {

    private boolean publishEnabled = true;
}
