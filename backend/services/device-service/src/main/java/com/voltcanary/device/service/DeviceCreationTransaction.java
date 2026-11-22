package com.voltcanary.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DeviceCreationTransaction {

    private record Receipt(String digest, UUID deviceId) {}

    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    private final AccountService accounts;
    private final ObjectMapper mapper;
    private final EntityManager entities;

    @Transactional
    public DeviceResponse create(
        AuthenticatedUser actor,
        UUID accountId,
        DeviceRequest request,
        String key
    ) {
        jdbc.queryForObject("select id from accounts where id=? for update", UUID.class, accountId);
        if (!accounts.currentAccount(actor).getId().equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account scope changed");
        }
        String digest = digest(request);
        var receipts = jdbc.query(
            "select payload_digest,device_id from device_creation_receipts where user_id=? and account_id=? and idempotency_key=?",
            (row, index) -> new Receipt(row.getString(1), row.getObject(2, UUID.class)),
            actor.id(),
            accountId,
            key
        );
        if (!receipts.isEmpty()) {
            Receipt receipt = receipts.getFirst();
            if (!receipt.digest().equals(digest)) throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Idempotency key payload conflict"
            );
            DeviceResponse existing = devices.get(actor, receipt.deviceId());
            if ("DECOMMISSIONED".equals(existing.status())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Created device was decommissioned; it will not be recreated"
                );
            }
            return existing;
        }
        DeviceResponse created = devices.create(actor, request);
        entities.flush();
        Instant now = Instant.now();
        // Keep the receipt if the resource is deleted: a lost response must never cause re-creation.
        jdbc.update(
            "insert into device_creation_receipts(user_id,account_id,idempotency_key,payload_digest,device_id,created_at,retain_until) values(?,?,?,?,?,?,?)",
            actor.id(),
            accountId,
            key,
            digest,
            created.id(),
            Timestamp.from(now),
            Timestamp.from(now.plus(Duration.ofDays(31)))
        );
        return created;
    }

    @Scheduled(
        fixedDelayString = "${app.device.creation-receipt-cleanup-delay:3600000}",
        initialDelayString = "${app.device.creation-receipt-cleanup-delay:3600000}"
    )
    @Transactional
    public void purgeExpired() {
        jdbc.update(
            "delete from device_creation_receipts where retain_until<?",
            Timestamp.from(Instant.now())
        );
    }

    private String digest(DeviceRequest request) {
        try {
            byte[] body = mapper
                .writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot encode device request", failure);
        }
    }
}
