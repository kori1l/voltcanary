package com.voltcanary.common.web;

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Preserve the application's Jackson 2 DTO/JsonNode contracts on Spring Framework 7. */
public final class JacksonRestClients {

    private JacksonRestClients() {}

    public static RestClient.Builder builder() {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
        );
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        return RestClient.builder()
            .requestFactory(factory)
            .messageConverters(converters -> {
                int jsonIndex = java.util.stream.IntStream.range(0, converters.size())
                    .filter(
                        index ->
                            converters.get(index) instanceof MappingJackson2HttpMessageConverter ||
                            converters.get(index) instanceof
                                org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
                    )
                    .findFirst()
                    .orElse(converters.size());
                converters.removeIf(
                    converter ->
                        converter instanceof MappingJackson2HttpMessageConverter ||
                        converter instanceof
                            org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
                );
                var json = new MappingJackson2HttpMessageConverter();
                json.getObjectMapper().disable(
                    com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
                );
                // Keep String/byte converters ahead of JSON and JSON ahead of optional YAML/CBOR codecs.
                converters.add(Math.min(jsonIndex, converters.size()), json);
            });
    }
}
