package com.voltcanary.identity.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.browser")
@Getter
@Setter
public class BrowserSessionProperties {

    private boolean secureCookie = true;
    private List<String> allowedOrigins = List.of(
        "http://localhost:3000",
        "https://localhost:8443"
    );
}
