INSERT INTO telemetry.measurements
    (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at, account_id)
SELECT r.device_id, r.source_id, r.recorded_at, field.key,
       CASE WHEN field.value->>'value' = 'true' THEN 1 ELSE 0 END,
       'boolean',
       CASE WHEN EXISTS (SELECT 1 FROM telemetry_quality_flags q WHERE q.reading_id=r.id AND q.flag='BACKFILL') THEN 'BACKFILL'
            WHEN EXISTS (SELECT 1 FROM telemetry_quality_flags q WHERE q.reading_id=r.id AND q.flag='OUT_OF_ORDER') THEN 'OUT_OF_ORDER'
            ELSE 'OK' END,
       COALESCE(r.received_at,r.recorded_at), r.recorded_at, r.account_id
FROM telemetry_readings r
CROSS JOIN LATERAL jsonb_each(CASE
    WHEN jsonb_typeof(r.metrics_json::jsonb->'configuredFields')='object'
    THEN r.metrics_json::jsonb->'configuredFields'
    WHEN NOT r.metrics_json::jsonb ? 'configuredFields'
         AND jsonb_typeof(r.metrics_json::jsonb->'transport'->'doorsOpen')='boolean'
         AND EXISTS (SELECT 1 FROM telemetry.positions p WHERE p.reading_id=r.id)
    THEN jsonb_build_object('transport.doorsOpen',jsonb_build_object('type','boolean','value',r.metrics_json::jsonb->'transport'->'doorsOpen'))
    ELSE '{}'::jsonb END) AS field
WHERE field.value->>'type'='boolean'
  AND jsonb_typeof(field.value->'value')='boolean'
  AND field.key ~ '^[A-Za-z][A-Za-z0-9_.]{0,63}$'
  AND r.source_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM telemetry_quality_flags q WHERE q.reading_id=r.id
      AND q.flag IN ('INVALID_VALUE','OUT_OF_RANGE','INVALID_TIMESTAMP','DUPLICATE','RATE_LIMITED'))
ON CONFLICT (device_id,source_id,recorded_at,metric_name) DO NOTHING;
