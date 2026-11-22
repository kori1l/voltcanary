package com.voltcanary.analytics.config;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTest {
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void configuredTimeoutBoundsAConnectedButSilentDownstream() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/silent", exchange -> {
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new AnalyticsDownstreamProperties();
            properties.getTelemetryService().setTimeout(Duration.ofMillis(100));
            var client = new RestClientConfig().telemetryRestClientBuilder(properties).build();
            assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/silent")
                    .retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(HttpTimeoutException.class);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void infiniteDownstreamTimeoutIsRejected() {
        var properties = new AnalyticsDownstreamProperties();
        properties.getMlService().setTimeout(Duration.ZERO);
        assertThatThrownBy(() -> new RestClientConfig().mlRestClientBuilder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
