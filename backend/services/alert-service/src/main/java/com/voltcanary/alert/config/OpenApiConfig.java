package com.voltcanary.alert.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    @Bean
    OpenAPI alertOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VoltCanary Alert API")
                        .version("v1")
                        .description("Alert lifecycle, deduplication, escalation, notification channels, and delivery attempts."))
                .schemaRequirement("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .schemaRequirement("internalServiceToken", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(INTERNAL_SERVICE_TOKEN_HEADER))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
