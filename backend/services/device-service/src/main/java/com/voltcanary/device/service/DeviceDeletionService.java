package com.voltcanary.device.service;

import com.voltcanary.device.event.SourceRuntimeConfigEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.domain.SourceStatus;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DeviceDeletionService {
    public record Receipt(UUID deviceId, String name, String deviceType, boolean deleteHistory, String status,
            String completedSteps, String lastError, Instant createdAt) { }
    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    private final DeviceRepository registry;
    private final DataSourceRepository sources;
    private final AccountService accounts;
    private final DeviceRegistryEventPublisher events;
    private final ObjectMapper mapper;
    private final SourceRuntimeConfigEventPublisher runtimeEvents;

    @Transactional
    public Receipt begin(AuthenticatedUser user, UUID id, boolean deleteHistory) {
        UUID account = accounts.currentAccount(user).getId();
        registry.flush();
        jdbc.queryForObject("select id from accounts where id=? for update", UUID.class, account);
        var prior = list(user).stream().filter(row -> row.deviceId().equals(id)).findFirst();
        if (prior.isPresent()) {
            if (prior.get().deleteHistory() != deleteHistory) throw new ResponseStatusException(HttpStatus.CONFLICT, "Deletion history policy is already confirmed");
            return prior.get();
        }
        var device = registry.lockOwned(id, account).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        DeviceResponse snapshot = devices.get(user, id);
        Instant now = Instant.now();
        var bindings = sources.findByDeviceIdIn(List.of(id));
        List<String> sourceIds = bindings.stream().map(source -> source.getSourceId()).toList();
        for (var source : bindings) {
            source.setEnabled(false);
            source.setStatus(SourceStatus.REVOKED);
            source.setTokenRevokedAt(now);
            source.setIngestionTokenHash(null);
            source.setTokenPrefix(null);
            source.setCredentialsRef(null);
            source.setConnectionRef(null);
            source.setCredentialReferencesJson("{}");
            source.setUpdatedAt(now);
            sources.save(source);
            runtimeEvents.publish(account, source);
            events.publish("DATA_SOURCE", "DEVICE_DELETED_REVOKED", account, id, source.getId(), "REVOKED");
        }
        devices.decommission(user, id);
        device.setDeletedAt(now);
        registry.save(device);
        jdbc.update("insert into device_deletions(device_id,account_id,user_id,name,device_type,delete_history,source_ids_json,status,completed_steps,created_at,updated_at) "
                + "values (?,?,?,?,?,?,?,'PENDING','',?,?)", id, account, user.id(), snapshot.name(), snapshot.type(), deleteHistory,
                json(sourceIds), Timestamp.from(now), Timestamp.from(now));
        return receipt(user, id);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Receipt> list(AuthenticatedUser user) {
        UUID account = accounts.currentAccount(user).getId();
        return jdbc.query("select * from device_deletions where account_id=? order by created_at desc", (rs, row) ->
                new Receipt(rs.getObject("device_id", UUID.class), rs.getString("name"), rs.getString("device_type"),
                        rs.getBoolean("delete_history"), rs.getString("status"), rs.getString("completed_steps"),
                        rs.getString("last_error"), rs.getTimestamp("created_at").toInstant()), account);
    }

    public Receipt receipt(AuthenticatedUser user, UUID id) {
        return list(user).stream().filter(row -> row.deviceId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deletion not found"));
    }

    public boolean retainedAccess(UUID device, UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select count(*) > 0 from device_deletions deletion join accounts account on account.id=deletion.account_id "
                + "where deletion.device_id=? and account.user_id=? and account.status='ACTIVE' and deletion.delete_history=false", Boolean.class, device, user));
    }

    public Map<String, Object> internalReceipt(UUID id) {
        var rows = jdbc.queryForList("select device_id,delete_history from device_deletions where device_id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Deletion not found");
        return Map.of("deviceId", id, "deleteHistory", rows.getFirst().get("delete_history"),
                "sourceIds", jdbc.query("select id from data_sources where device_id=?", (rs, index) -> rs.getObject(1, UUID.class), id));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid deletion metadata", invalid); }
    }
}
