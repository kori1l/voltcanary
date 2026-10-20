package com.voltcanary.telemetry.repository;

/** Loose metric-key scan plus two indexed tenant candidates, including legacy null-account data. */
public final class LatestTelemetrySql {
    private LatestTelemetrySql() { }
    public static String forDevices(int count) {
        String devices = String.join(",", java.util.Collections.nCopies(count, "(?::uuid)"));
        return """
                WITH RECURSIVE keys(device_id,metric_name) AS (
                  SELECT d.id,m.metric_name FROM (VALUES %s) d(id)
                  CROSS JOIN LATERAL (
                    SELECT metric_name FROM telemetry.measurements
                    WHERE device_id=d.id AND metric_name<>'_legacy' AND metric_value BETWEEN -1e308 AND 1e308
                    ORDER BY metric_name LIMIT 1
                  ) m
                  UNION ALL
                  SELECT k.device_id,m.metric_name FROM keys k CROSS JOIN LATERAL (
                    SELECT metric_name FROM telemetry.measurements
                    WHERE device_id=k.device_id AND metric_name>k.metric_name AND metric_name<>'_legacy'
                    AND metric_value BETWEEN -1e308 AND 1e308 ORDER BY metric_name LIMIT 1
                  ) m
                ), boundary AS (SELECT ?::uuid account_id,?::timestamp as_of)
                SELECT sample.* FROM keys k CROSS JOIN boundary b CROSS JOIN LATERAL (
                  SELECT candidates.* FROM (
                    (SELECT device_id,metric_name,unit,metric_value,source_id,recorded_at,received_at,quality
                     FROM telemetry.measurements WHERE device_id=k.device_id AND metric_name=k.metric_name AND metric_name<>'_legacy'
                     AND account_id=b.account_id AND recorded_at<=b.as_of AND metric_value BETWEEN -1e308 AND 1e308
                     ORDER BY recorded_at DESC,source_id DESC LIMIT 1)
                    UNION ALL
                    (SELECT device_id,metric_name,unit,metric_value,source_id,recorded_at,received_at,quality
                     FROM telemetry.measurements WHERE device_id=k.device_id AND metric_name=k.metric_name AND metric_name<>'_legacy'
                     AND account_id IS NULL AND recorded_at<=b.as_of AND metric_value BETWEEN -1e308 AND 1e308
                     ORDER BY recorded_at DESC,source_id DESC LIMIT 1)
                  ) candidates ORDER BY recorded_at DESC,source_id DESC LIMIT 1
                ) sample ORDER BY sample.device_id,sample.metric_name
                """.formatted(devices);
    }
}
