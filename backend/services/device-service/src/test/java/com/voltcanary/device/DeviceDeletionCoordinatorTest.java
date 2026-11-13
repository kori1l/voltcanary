package com.voltcanary.device;

import com.sun.net.httpserver.HttpServer;
import com.voltcanary.device.service.DeviceDeletionCoordinator;
import com.voltcanary.device.service.DeviceDeletionFinalization;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DeviceDeletionCoordinatorTest {
    @Test
    void retriesUnavailableParticipantWithoutRepeatingCompletedCleanup() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table device_deletions(device_id uuid primary key,completed_steps varchar(255),source_ids_json text,status varchar(30),last_error varchar(255),created_at timestamp,updated_at timestamp)");
        UUID device = UUID.randomUUID();
        jdbc.update("insert into device_deletions values(?,'','[\"hsl-bus-test\"]','PENDING',null,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", device);
        AtomicBoolean available = new AtomicBoolean(false);
        var servers = new ArrayList<HttpServer>();
        var calls = new ArrayList<AtomicInteger>();
        var urls = new ArrayList<String>();
        try {
            for (int i = 0; i < 5; i++) {
                int participant = i;
                var count = new AtomicInteger();
                calls.add(count);
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/internal/device-deletions/", exchange -> {
                    count.incrementAndGet();
                    assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")).isEqualTo("test-internal");
                    exchange.sendResponseHeaders(participant == 0 && !available.get() ? 503 : 200, -1);
                    exchange.close();
                });
                server.start(); servers.add(server);
                urls.add("http://127.0.0.1:" + server.getAddress().getPort());
            }
            var finalization = mock(DeviceDeletionFinalization.class);
            var coordinator = new DeviceDeletionCoordinator(jdbc, finalization, "test-internal", urls.get(0), urls.get(1), urls.get(2), urls.get(3), urls.get(4));
            coordinator.resume();
            verify(finalization, never()).complete(device);
            assertThat(jdbc.queryForObject("select status from device_deletions", String.class)).isEqualTo("PENDING");
            available.set(true);
            coordinator.resume();
            verify(finalization, times(1)).complete(device);
            assertThat(calls.get(0).get()).isEqualTo(2);
            for (int i = 1; i < 5; i++) assertThat(calls.get(i).get()).isEqualTo(1);
        } finally { servers.forEach(server -> server.stop(0)); }
    }
}
