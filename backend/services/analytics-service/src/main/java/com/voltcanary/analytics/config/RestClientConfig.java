package com.voltcanary.analytics.config;

import com.voltcanary.common.web.JacksonRestClients;
import java.time.Duration;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientBuilder() {
        return JacksonRestClients.builder();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder telemetryRestClientBuilder(AnalyticsDownstreamProperties properties) {
        return bounded(properties.getTelemetryService().getTimeout());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder deviceRestClientBuilder(AnalyticsDownstreamProperties properties) {
        return bounded(properties.getDeviceService().getTimeout());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder alertRestClientBuilder(AnalyticsDownstreamProperties properties) {
        return bounded(properties.getAlertService().getTimeout());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder mlRestClientBuilder(AnalyticsDownstreamProperties properties) {
        return bounded(properties.getMlService().getTimeout());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder mlTrainingRestClientBuilder(AnalyticsDownstreamProperties properties) {
        return bounded(properties.getMlTrainingTimeout());
    }

    private RestClient.Builder bounded(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Downstream timeout must be positive");
        }
        var factory = new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(
                    timeout.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : timeout
                )
                .build()
        );
        factory.setReadTimeout(timeout);
        return JacksonRestClients.builder().requestFactory(factory);
    }
}
