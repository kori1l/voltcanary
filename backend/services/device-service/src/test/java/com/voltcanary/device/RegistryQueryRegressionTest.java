package com.voltcanary.device;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.MaintenanceWindow;
import com.voltcanary.device.repository.AccountRepository;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.DeviceService;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.kafka.publish-enabled=false", "app.kafka.consume-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:registry-query-boundaries;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.open-in-view=false"
})
class RegistryQueryRegressionTest {
    @Autowired private AccountRepository accounts;
    @Autowired private DeviceRepository devices;
    @Autowired private DeviceTypeRepository types;
    @Autowired private MaintenanceWindowRepository windows;
    @Autowired private DeviceService service;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private record Owner(Account account, AuthenticatedUser user) { }

    private Owner owner() {
        var account = new Account();
        account.setId(UUID.randomUUID());
        account.setUserId(UUID.randomUUID());
        account.setName("Account");
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accounts.save(account);
        return new Owner(account, new AuthenticatedUser(account.getUserId(), "query@example.test", "USER"));
    }

    private Device device(Owner owner, String name) {
        var device = new Device();
        device.setId(UUID.randomUUID());
        device.setAccountId(owner.account().getId());
        device.setDeviceTypeId(types.findAll().getFirst().getId());
        device.setName(name);
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCreatedAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        return devices.save(device);
    }

    @Test
    void pageLoadsOnlyItsDevicesAndJoinedTypesAndTreatsSearchWildcardsLiterally() {
        var owner = owner();
        for (int index = 0; index < 35; index++) device(owner, "Device " + index);
        device(owner, "Literal % marker");
        device(owner(), "Another account");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var page = service.list(owner.user(), null, null, null, null, 10, 0);
        assertThat(page).hasSize(10);
        assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(12);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        assertThat(service.list(owner.user(), null, null, null, "%", 10, 0)).singleElement()
                .extracting(com.voltcanary.device.dto.DeviceResponse::name).isEqualTo("Literal % marker");
    }

    @Test
    void compoundCursorReturnsAllRowsWithEqualTimestamp() {
        var owner = owner();
        var device = device(owner, "Maintained");
        Instant time = Instant.parse("2026-11-22T00:00:00Z");
        var expected = new ArrayList<UUID>();
        for (int index = 0; index < 3; index++) {
            var row = new MaintenanceWindow();
            row.setId(UUID.randomUUID()); row.setAccountId(owner.account().getId()); row.setDeviceId(device.getId());
            row.setTitle("Window"); row.setStartAt(time); row.setEndAt(time.plusSeconds(60));
            row.setCreatedAt(time); row.setUpdatedAt(time); row.setVersion(1);
            windows.save(row); expected.add(row.getId());
        }
        var first = windows.findChangesAfter(Instant.EPOCH, null, PageRequest.of(0, 2));
        var last = first.getLast();
        var second = windows.findChangesAfter(last.getUpdatedAt(), last.getId(), PageRequest.of(0, 2));
        var actual = new ArrayList<>(first.stream().map(MaintenanceWindow::getId).toList());
        actual.addAll(second.stream().map(MaintenanceWindow::getId).toList());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
