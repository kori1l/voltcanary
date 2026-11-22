package com.voltcanary.telemetry.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VehicleMonitoringRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public VehicleMonitoringRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.postgres = Boolean.TRUE.equals(
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                    connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
            )
        );
    }

    public VehicleMonitoringSettings get(UUID user, UUID device) {
        return jdbc
            .query(
                "select settings from telemetry.vehicle_monitoring where user_id=? and device_id=?",
                (row, index) -> {
                    try {
                        return mapper.readValue(row.getString(1), VehicleMonitoringSettings.class);
                    } catch (Exception exception) {
                        throw new IllegalStateException(
                            "Invalid saved monitoring settings",
                            exception
                        );
                    }
                },
                user,
                device
            )
            .stream()
            .findFirst()
            .orElseGet(VehicleMonitoringSettings::defaults);
    }

    @Transactional
    public VehicleMonitoringSettings save(
        UUID user,
        UUID device,
        VehicleMonitoringSettings settings
    ) {
        settings.validate();
        final String json;
        try {
            json = mapper.writeValueAsString(settings);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid monitoring settings", exception);
        }
        String sql = postgres
            ? "insert into telemetry.vehicle_monitoring (user_id,device_id,settings,updated_at) values (?,?,?,?) on conflict (user_id,device_id) do update set settings=excluded.settings,updated_at=excluded.updated_at"
            : "merge into telemetry.vehicle_monitoring (user_id,device_id,settings,updated_at) key(user_id,device_id) values (?,?,?,?)";
        jdbc.update(sql, user, device, json, Timestamp.from(Instant.now()));
        return settings;
    }
}
