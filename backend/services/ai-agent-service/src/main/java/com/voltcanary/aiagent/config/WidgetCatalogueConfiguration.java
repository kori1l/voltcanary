package com.voltcanary.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.springai.SpringAiCapabilityEmbeddings;
import com.voltcanary.aiagent.service.WidgetCatalogueSearch;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WidgetEmbeddingProperties.class)
public class WidgetCatalogueConfiguration {
    @Bean
    WidgetCatalogueSearch widgetCatalogueSearch(ObjectMapper mapper, WidgetEmbeddingProperties properties,
            io.micrometer.observation.ObservationRegistry observations) {
        return new WidgetCatalogueSearch(mapper, properties.enabled() ? new SpringAiCapabilityEmbeddings(properties, observations) : null);
    }
}
