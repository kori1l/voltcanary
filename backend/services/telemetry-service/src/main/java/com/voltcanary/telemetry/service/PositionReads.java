package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.dto.PositionEvidence;
import com.voltcanary.telemetry.repository.PositionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PositionReads {
    private final PositionRepository repository;
    public PositionReads(PositionRepository repository) {
        this.repository = repository;
    }

    public PositionEvidence.Latest latest(UUID device, String source, Instant asOf) {
        var point = repository.latest(device, source, asOf);
        String freshness = point == null ? "NO_DATA" : Duration.between(point.recordedAt(), asOf).compareTo(Duration.ofSeconds(30)) > 0 ? "STALE" : "FRESH";
        return new PositionEvidence.Latest(device, source, asOf, freshness, point);
    }

    /** Call after every fleet device/source has been authorized. */
    public PositionEvidence.Fleet fleetEvidence(java.util.Map<UUID, String> targets, Instant from, Instant to, Instant asOf) {
        Instant end = asOf.isAfter(to) ? to : asOf;
        Instant start = end.minus(Duration.ofMinutes(15));
        if (from.isAfter(start)) start = from;
        requireRange(start, end);
        var latest = new ArrayList<PositionEvidence.Latest>();
        var tracks = new ArrayList<PositionEvidence.History>();
        for (var target : new java.util.TreeMap<>(targets).entrySet()) {
            latest.add(latest(target.getKey(), target.getValue(), end));
            tracks.add(history(target.getKey(), target.getValue(), start, end));
        }
        return new PositionEvidence.Fleet(List.copyOf(latest), List.copyOf(tracks));
    }

    public PositionEvidence.History history(UUID device, String source, Instant from, Instant to) {
        requireRange(from, to);
        var descending = repository.history(device, source, from, to);
        var points = new ArrayList<>(descending.subList(0, Math.min(1000, descending.size())));
        Collections.reverse(points);
        return new PositionEvidence.History(device, source, from, to, points, points.size(), descending.size() > 1000);
    }

    public PositionEvidence.Evidence evidence(UUID device, String source, Instant from, Instant to, Instant asOf) {
        return new PositionEvidence.Evidence(latest(device, source, asOf.isAfter(to) ? to : asOf), history(device, source, from, to));
    }

    public static void requireRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Position range must be positive and at most 24 hours");
        }
    }

    public PositionEvidence.Recording recordings(UUID device, String source, Instant from, Instant to, Instant cursor, int limit) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || limit < 1 || limit > 2000 || cursor != null && (cursor.isBefore(from) || !cursor.isBefore(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recordings require positive range <=31 days, cursor in range, limit 1–2000");
        }
        var found = repository.recordings(device, source, cursor == null ? from : cursor, to, limit + 1);
        boolean more = found.size() > limit;
        var points = List.copyOf(found.subList(0, Math.min(limit, found.size())));
        return new PositionEvidence.Recording(points, more ? points.get(points.size() - 1).recordedAt() : null, more);
    }
}
