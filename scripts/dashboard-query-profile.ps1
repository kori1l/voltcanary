param([string]$Gateway = 'http://127.0.0.1:8080', [string]$SummaryPath = 'artifacts/dashboard-query-profile.json', [switch]$FailureProfile)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$taskClient = [System.Net.Http.HttpClient]::new()
$taskClient.Timeout = [TimeSpan]::FromSeconds(20)
function Invoke-ProfileApi($Method, $Path, $Body, $Headers, $Expected = 200) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$Gateway$Path")
    if ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    foreach ($key in $Headers.Keys) { $request.Headers.Add($key, [string]$Headers[$key]) }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = $taskClient.SendAsync($request).GetAwaiter().GetResult()
    try {
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $watch.Stop()
        if ([int]$response.StatusCode -ne $Expected) { throw "Profile request $Method $Path returned $([int]$response.StatusCode)." }
        return [pscustomobject]@{ Body = ($text | ConvertFrom-Json); Milliseconds = $watch.Elapsed.TotalMilliseconds }
    } finally { $response.Dispose(); $request.Dispose() }
}
function Invoke-ProfileSql($Service, $Database, $Sql) {
    $Sql | docker compose exec -T $Service sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh $Database | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Fixture SQL failed in $Database." }
}
function New-ProfileWidget($Id, $Type, $Binding, $Query) {
    return @{ id = $Id; type = $Type; typeVersion = 1; title = $Type; binding = $Binding; query = $Query; presentation = @{}; navigation = @{}; payload = @{} }
}
$taskTelemetryStopped = $false
try {
    $credentials = @{ email = "dashboard-query-$([Guid]::NewGuid().ToString('N'))@example.invalid"; password = "Vc9!$([Guid]::NewGuid().ToString('N'))" }
    $registered = Invoke-ProfileApi 'POST' '/auth/register' $credentials @{}
    $login = Invoke-ProfileApi 'POST' '/auth/login' $credentials @{}
    $headers = @{ Authorization = "Bearer $($login.Body.accessToken)" }
    $account = (Invoke-ProfileApi 'GET' '/accounts/current' $null $headers).Body.id
    if (-not [Guid]::TryParse($account, [ref]([Guid]::Empty))) { throw 'Invalid fixture account.' }
    $ids = @(1..100 | ForEach-Object { [Guid]::NewGuid().ToString() })
    $values = @($ids | ForEach-Object { "('$_','$account','00000000-0000-0000-0000-000000000001','Query fixture','ACTIVE',now(),now())" }) -join ','
    Invoke-ProfileSql 'postgres' 'voltcanary_device' "INSERT INTO devices(id,account_id,device_type_id,name,status,created_at,updated_at) VALUES $values;"
    $to = [DateTime]::UtcNow.AddSeconds(-2)
    $from = $to.AddDays(-1)
    $fromText = $from.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $toText = $to.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $idSql = ($ids | ForEach-Object { "('$_'::uuid)" }) -join ','
    Invoke-ProfileSql 'timescaledb' 'voltcanary_telemetry' @"
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '$account'::uuid,d.id,'dashboard-query-fixture',t,'activePowerW',CASE WHEN t>'$toText'::timestamp-interval '20 minutes' THEN 100 ELSE 0 END,'W','OK',now(),now()
FROM (VALUES $idSql) d(id) CROSS JOIN generate_series('$fromText'::timestamp,'$toText'::timestamp-interval '1 second',interval '10 minutes') t;
"@
    $fixedOne = @{ mode = 'FIXED'; deviceIds = @($ids[0]) }
    $widgets = @(
        (New-ProfileWidget 'fleet' 'fleet-count' @{} @{}),
        (New-ProfileWidget 'latest' 'latest-value' $fixedOne @{ metric = 'activePowerW'; unit = 'W' }),
        (New-ProfileWidget 'series' 'time-series' $fixedOne @{ series = @(@{ deviceId = $ids[0]; metric = 'activePowerW' }); unit = 'W'; reducer = 'RAW' }),
        (New-ProfileWidget 'summary' 'metric-summary' @{} @{ metric = 'activePowerW'; unit = 'W'; reducer = 'MEAN'; compare = @{ mode = 'PREVIOUS' } }),
        (New-ProfileWidget 'incidents' 'incident-list' @{} @{}),
        (New-ProfileWidget 'maintenance' 'maintenance-agenda' @{} @{}),
        (New-ProfileWidget 'risk' 'risk-list' @{} @{}),
        (New-ProfileWidget 'quality' 'quality-and-freshness' @{} @{}),
        (New-ProfileWidget 'matrix' 'location-matrix' @{} @{}),
        (New-ProfileWidget 'prediction' 'prediction-evidence' $fixedOne @{}),
        (New-ProfileWidget 'note' 'context-note' @{} @{}),
        (New-ProfileWidget 'fleet-repeat' 'fleet-count' @{} @{})
    )
    $definition = @{ schemaVersion = 1; name = 'Measured local query profile'; description = ''; defaultContext = @{ time = @{ mode = 'FIXED'; timezone = 'UTC'; from = $fromText; to = $toText } }; sections = @(); widgets = $widgets; layouts = @{} }
    $created = Invoke-ProfileApi 'POST' '/dashboards' @{ definition = $definition } (@{} + $headers + @{ 'Idempotency-Key' = [Guid]::NewGuid().ToString() }) 201
    $id = $created.Body.id
    $draft = (Invoke-ProfileApi 'GET' "/dashboards/$id/draft" $null $headers).Body
    $published = Invoke-ProfileApi 'POST' "/dashboards/$id/publish" @{ draftEtag = $draft.etag } (@{} + $headers + @{ 'If-Match' = '"' + $draft.etag + '"'; 'Idempotency-Key' = [Guid]::NewGuid().ToString() })
    $validated = Invoke-ProfileApi 'POST' "/dashboards/$id/query-validation" @{} $headers
    if (@($validated.Body.panels | Where-Object status -ne 'valid').Count) { throw 'Published query capability validation failed.' }
    $cold = @()
    1..3 | ForEach-Object {
        $result = Invoke-ProfileApi 'POST' "/dashboards/$id/query" @{ forceRefresh = $true } $headers
        if (@($result.Body.panels | Where-Object status -NotIn @('ready','no_data')).Count) {
            $failures = @($result.Body.panels | Where-Object status -NotIn @('ready','no_data') | Select-Object widgetId,status,errorCode,explanation)
            $failures | ConvertTo-Json -Depth 5 | Write-Output
            throw 'Cold profile contains unsuccessful panels.'
        }
        $cold += $result.Milliseconds
        $fleet = ($result.Body.panels | Where-Object widgetId -eq 'fleet').data.counts.registered
        $samples = ($result.Body.panels | Where-Object widgetId -eq 'summary').data.summary.sampleCount
        if ($fleet -ne 100 -or $samples -ne 14400) { throw 'Profile scope or full-data summary denominator differs from the fixture.' }
    }
    $warm = @()
    1..10 | ForEach-Object {
        $result = Invoke-ProfileApi 'POST' "/dashboards/$id/query" @{} $headers
        if (-not $result.Body.cached) { throw 'Warm profile did not use an authorization-renewed cache entry.' }
        $warm += $result.Milliseconds
    }
    $failed = $null
    if ($FailureProfile) {
        docker compose stop telemetry-service | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot start failed-dependency profile.' }
        $taskTelemetryStopped = $true
        $result = Invoke-ProfileApi 'POST' "/dashboards/$id/query" @{ forceRefresh = $true } $headers
        $failed = @{ milliseconds = $result.Milliseconds; panels = @($result.Body.panels | Select-Object widgetId,status,errorCode) }
        if (($result.Body.panels | Where-Object widgetId -eq 'incidents').status -notin @('ready','no_data')) { throw 'Incident panel did not survive telemetry outage.' }
        if (($result.Body.panels | Where-Object widgetId -eq 'fleet').status -ne 'unavailable') { throw 'Failed telemetry was presented as data.' }
    }
    $ordered = @($warm | Sort-Object)
    $p95 = $ordered[[Math]::Ceiling($ordered.Count * 0.95) - 1]
    $summary = @{ checkedAt = [DateTime]::UtcNow.ToString('o'); environment = 'LOCAL_COMPOSE_SINGLE_QUERY_SYNTHETIC_FIXTURE'; devices = 100; widgets = 12; intervalHours = 24; samples = 14400; dashboardId = $id; coldMilliseconds = $cold; warmMilliseconds = $warm; warmP95Milliseconds = $p95; failedDependency = $failed; meaningfulResultDelivery = 'AT_COMPLETED_BATCH_RESPONSE'; coldTargetMet = (($cold | Measure-Object -Maximum).Maximum -le 2000); warmTargetMet = ($p95 -le 5000); capacityClaim = 'NONE' }
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    if (-not $summary.coldTargetMet -or -not $summary.warmTargetMet) { throw 'Measured query latency target was not met; evidence saved.' }
    Write-Output "Query profile passed: cold max $([Math]::Round(($cold | Measure-Object -Maximum).Maximum)) ms; warm p95 $([Math]::Round($p95)) ms."
} finally {
    if ($taskTelemetryStopped) { docker compose --profile app up -d --no-deps telemetry-service | Out-Null }
    $taskClient.Dispose()
}
