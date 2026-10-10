package com.voltcanary.dashboard.query;

import com.voltcanary.dashboard.query.QueryTypes.Manifest;
import com.voltcanary.dashboard.query.QueryTypes.Reducer;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WidgetManifests {
    private static final List<String> UNITS = List.of("W", "V", "A", "Wh", "kWh", "C", "%", "Hz", "dBm", "mm/s", "h", "ratio");
    private final List<Manifest> catalog = List.of(
            manifest("fleet-count", "Fleet registration and freshness", "COUNT", List.of(), 0, 0, "SNAPSHOT"),
            manifest("latest-value", "Latest measured value", "VALUE", List.of(Reducer.RAW), 1, 1, "SNAPSHOT"),
            manifest("time-series", "Measured time series", "SERIES", List.of(Reducer.RAW, Reducer.AVG, Reducer.MIN, Reducer.MAX), 8, 50, "INTERVAL"),
            manifest("metric-summary", "Full measurement summary", "SUMMARY", List.of(Reducer.MIN, Reducer.MAX, Reducer.MEAN, Reducer.P95), 0, 0, "INTERVAL"),
            manifest("incident-list", "Incident facts", "INCIDENTS", List.of(), 0, 0, "SNAPSHOT_OR_CREATED_INTERVAL"),
            manifest("maintenance-agenda", "Scheduled maintenance", "MAINTENANCE", List.of(), 0, 0, "EXPLICIT_INTERVAL"),
            manifest("risk-list", "Recorded risk evidence", "RISK", List.of(), 0, 0, "SNAPSHOT"),
            manifest("quality-and-freshness", "Measurement quality and age", "QUALITY", List.of(), 0, 0, "SNAPSHOT_AND_INTERVAL"),
            manifest("location-matrix", "Single-account location evidence", "MATRIX", List.of(), 0, 0, "COLUMN_TIME_BASIS"),
            manifest("prediction-evidence", "Recorded prediction evidence", "PREDICTION", List.of(), 0, 50, "SNAPSHOT"),
            manifest("context-note", "Operator context note", "NOTE", List.of(), 0, 0, "NONE"),
            manifest("energy-period", "Energy consumption and coverage", "ENERGY", List.of(), 0, 0, "INTERVAL"),
            manifest("peak-and-ranking", "Energy and recorded equipment peak ranking", "ENERGY_RANKING", List.of(), 0, 0, "INTERVAL"),
            unsupported("estimated-cost", "Energy and versioned tariffs are required"),
            unsupported("uptime-or-cycle", "Confirmed downtime/cycle contracts are required"));

    public List<Manifest> all() {
        return catalog;
    }

    public Optional<Manifest> find(String type, int version) {
        return catalog.stream().filter(manifest -> manifest.type().equals(type) && manifest.typeVersion() == version).findFirst();
    }

    private Manifest manifest(String type, String title, String resultType, List<Reducer> reducers,
            int series, int detail, String basis) {
        return new Manifest(type, 1, title, resultType, true, null, reducers,
                reducers.isEmpty() ? List.of() : UNITS, series, detail, 50, basis, size(type));
    }

    private Manifest unsupported(String type, String reason) {
        return new Manifest(type, 1, type, "UNSUPPORTED", false, reason, List.of(), List.of(), 0, 0, 0, "NONE", size(type));
    }

    private QueryTypes.SizeConstraints size(String type) {
        return new QueryTypes.SizeConstraints(1, 1, 1, 1);
    }
}
