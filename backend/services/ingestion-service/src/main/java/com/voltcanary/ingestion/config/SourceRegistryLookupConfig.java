package com.voltcanary.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.source.SourceRegistryLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceRegistryLookupConfig {

    @Bean
    public SourceRegistryLookup sourceRegistryLookup(
        ObjectMapper mapper,
        @Value("${app.device-service.base-url}") String baseUrl,
        @Value("${app.internal.service-token}") String serviceToken
    ) {
        return new SourceRegistryLookup(mapper, baseUrl, serviceToken);
    }
}
