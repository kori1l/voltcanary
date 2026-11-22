package com.voltcanary.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.gateway.config.GatewayCorsProperties;
import com.voltcanary.gateway.filter.CorsPreflightGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class DashboardCorsTest {

    @Test
    void draftAndPublicationHeadersAreAllowedAndEtagIsVisible() {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.options("/dashboards/test/draft")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                    "authorization,if-match,idempotency-key"
                )
        );
        new CorsPreflightGlobalFilter(new GatewayCorsProperties())
            .filter(exchange, request -> Mono.empty())
            .block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
            exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)
        ).contains("If-Match", "Idempotency-Key");
        assertThat(
            exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)
        ).contains("ETag", "Retry-After");
    }
}
