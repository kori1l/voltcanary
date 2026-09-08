package com.voltcanary.ingestion.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionRuntimeConfiguration {

    @Bean
    public Clock ingestionClock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient ingestionHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
