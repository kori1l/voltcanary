package com.voltcanary.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.request-size")
@Getter
@Setter
public class GatewayRequestSizeProperties {

    private long defaultMaxBytes = 1_048_576;
    private long authMaxBytes = 65_536;
    private long ingestionMaxBytes = 262_144;
}
