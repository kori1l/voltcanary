package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.TelemetryAggregate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryAggregateRepository extends JpaRepository<TelemetryAggregate, UUID> {
}
