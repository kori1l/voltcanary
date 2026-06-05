package com.voltcanary.device.config;

import com.voltcanary.device.domain.Device;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI deviceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VoltCanary Device API")
                        .version("v1")
                        .description("Device inventory, locations, data sources, sensors, and analytics configuration."))
                .schemaRequirement("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .schemaRequirement("internalServiceToken", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(InternalServiceAuth.HEADER))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
