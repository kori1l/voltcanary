package com.voltcanary.gateway.config;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.rate-limit")
@Getter
@Setter
public class GatewayRateLimitProperties {

    private List<String> trustedProxyAddresses = List.of();
    private boolean enabled = true;
    private boolean redisEnabled = true;
    private Duration window = Duration.ofMinutes(1);
    private int authRequestsPerWindow = 30;
    private int ingestionRequestsPerWindow = 120;
    private int generalRequestsPerWindow = 600;
}
