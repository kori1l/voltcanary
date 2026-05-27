package com.voltcanary.gateway.error;

import com.voltcanary.gateway.filter.CorrelationIdGlobalFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class GatewayErrorResponseWriter {

    private GatewayErrorResponseWriter() {
    }

    public static Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = body(exchange, code, message).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static String body(ServerWebExchange exchange, String code, String message) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        return """
                {"code":"%s","message":"%s","path":"%s","correlationId":%s,"timestamp":"%s"}\
                """
                .formatted(
                        escape(code),
                        escape(message),
                        escape(exchange.getRequest().getURI().getPath()),
                        correlationId == null ? "null" : "\"" + escape(correlationId) + "\"",
                        Instant.now());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
