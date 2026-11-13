$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$compose = Join-Path $root "deploy/docker-compose/device-variables-smoke.yml"
$fixture = Get-Content -LiteralPath (Join-Path $root "artifacts/device-variables-smoke-summary.json") -Raw | ConvertFrom-Json
if (-not $fixture.dockerServices) { throw "Requires the running isolated -DockerServices fixture" }
$device = ([guid]$fixture.deviceIds[0]).ToString()
$sourceId = ([guid]$fixture.sourceIds[0]).ToString()
$variable = ([guid]$fixture.variableId).ToString()
$session = Invoke-RestMethod -Method POST -Uri "http://127.0.0.1:18081/auth/login" -ContentType "application/json" -Body (ConvertTo-Json @{email=$fixture.email;password="Variable-smoke-2026!"})
$ownerHeaders = @{Authorization=("Bearer " + $session.accessToken)}
function Invoke-Fixture([int]$Port, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $arguments = @{Uri="http://localhost:$Port$Path";Headers=$Headers;TimeoutSec=10;Method="GET"}
    if ($null -ne $Body) {$arguments.Method="POST";$arguments.ContentType="application/json";$arguments.Body=ConvertTo-Json -InputObject $Body -Depth 20 -Compress}
    Invoke-RestMethod @arguments
}
function Await-Fixture([scriptblock]$Read, [scriptblock]$Accept, [string]$Description) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        try {$value=& $Read;if (& $Accept $value) {return $value}} catch {$failure=$_.Exception.Message}
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description. $failure"
}
function Sql([string]$Text) {
    $value = & docker compose -f $compose exec -T timescale psql -U variable -d telemetry -tAc $Text
    if ($LASTEXITCODE -ne 0) {throw "Fixture query failed"}
    return ("$value").Trim()
}
function Require([bool]$Condition, [string]$Message) {if (-not $Condition) {throw $Message}}
$source = Invoke-Fixture 18082 "/devices/$device/data-sources/$sourceId" $null $ownerHeaders
$credential = Invoke-Fixture 18082 "/devices/$device/data-sources/$sourceId/token" @{} $ownerHeaders
$sourceHeaders = @{Authorization=("Bearer " + $credential.token)}
$ingestionCommand = docker inspect --format '{{json .Config.Cmd}}' voltcanary-variable-smoke-ingestion-service | ConvertFrom-Json
$sharedCommand = @($ingestionCommand | ForEach-Object {$_.Replace("--app.ingestion.multi-replica-mode=false","--app.ingestion.multi-replica-mode=true").Replace("--app.idempotency.redis-enabled=false","--app.idempotency.redis-enabled=true").Replace("--app.ingestion.redis-rate-limit-enabled=false","--app.ingestion.redis-rate-limit-enabled=true").Replace("--app.ingestion.local-correctness-state-enabled=true","--app.ingestion.local-correctness-state-enabled=false")}) + @("--spring.data.redis.host=redis")
& docker compose -f $compose up -d --wait redis
if ($LASTEXITCODE -ne 0) {throw "Shared Redis failed"}
& docker rm -f voltcanary-variable-smoke-ingestion-service | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Could not replace owned ingestion fixture"}
& docker compose -f $compose run -d --service-ports --use-aliases --name voltcanary-variable-smoke-ingestion-service ingestion-service @sharedCommand | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Shared-state primary ingestion failed"}
& docker compose -f $compose run -d -p 18093:18083 --name voltcanary-variable-smoke-ingestion-replica ingestion-service @sharedCommand | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Second ingestion replica failed"}
foreach ($port in @(18083,18093)) {
    $null=Await-Fixture {Invoke-Fixture $port "/actuator/health"} {param($value) $value.status -eq "UP"} "ingestion replica health"
    $null=Await-Fixture {
        Invoke-Fixture $port "/ingestion/readings" @{deviceId=$device;sourceId=$source.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=235}} $sourceHeaders
    } {param($value) $value.acceptedCount -eq 1} "replica processing projection"
}
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
try {
    $body = ConvertTo-Json -Compress -Depth 10 @{deviceId=$device;sourceId=$source.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=235}}
    $requests=@();$tasks=@()
    foreach ($port in @(18083,18093)) {
        $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "http://localhost:$port/ingestion/readings")
        $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer",$credential.token)
        $request.Content = New-Object System.Net.Http.StringContent($body,[Text.Encoding]::UTF8,"application/json")
        $requests += $request;$tasks += $client.SendAsync($request)
    }
    $answers=foreach($task in $tasks) {
        $response=$task.GetAwaiter().GetResult()
        Require ($response.IsSuccessStatusCode) "Concurrent ingestion was rejected"
        $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $response.Dispose()
    }
    Require (($answers | Measure-Object acceptedCount -Sum).Sum -eq 1) "Replicas accepted the same input twice"
    Require (($answers | Measure-Object duplicateCount -Sum).Sum -eq 1) "Shared duplicate acknowledgement missing"
} finally {$client.Dispose();foreach($request in $requests) {$request.Dispose()}}
& docker stop voltcanary-variable-smoke-ingestion-service | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Could not stop primary fixture"}
$failover=Invoke-Fixture 18093 "/ingestion/readings" @{deviceId=$device;sourceId=$source.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=235}} $sourceHeaders
Require ($failover.acceptedCount -eq 1) "Surviving replica could not accept telemetry"
& docker start voltcanary-variable-smoke-ingestion-service | Out-Null

# Keep the raw consumer running while materialization is disabled; the event must park.
$telemetryCommand = docker inspect --format '{{json .Config.Cmd}}' voltcanary-variable-smoke-telemetry-service | ConvertFrom-Json
$disabledCommand = @($telemetryCommand | ForEach-Object {$_.Replace("--app.variables.enabled=true","--app.variables.enabled=false")})
& docker stop voltcanary-variable-smoke-telemetry-service | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Could not stop primary telemetry fixture"}
& docker compose -f $compose run -d -p 18094:18084 --name voltcanary-variable-smoke-telemetry-replica telemetry-service @disabledCommand | Out-Null
if ($LASTEXITCODE -ne 0) {throw "Rollback telemetry replica failed"}
$null=Await-Fixture {Invoke-Fixture 18094 "/actuator/health"} {param($value) $value.status -eq "UP"} "rollback telemetry health"
$rollbackReading=@{deviceId=$device;sourceId=$source.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=235}}
$null=Invoke-Fixture 18093 "/ingestion/readings" $rollbackReading $sourceHeaders
$parked=Await-Fixture {Sql "select event_id from variable_pending_events where status='PENDING' and diagnostic='PROCESSING_DISABLED' order by created_at desc limit 1"} {param($value) $value -match '^[a-f0-9-]{36}$'} "durable rollback parking"
$parkedId=([guid]$parked).ToString()
Require ((Sql "select count(*) from variable_measurement_lineage where source_event_id='$parkedId'") -eq "0") "Disabled processing wrote computed values"
& docker stop voltcanary-variable-smoke-telemetry-replica | Out-Null
& docker start voltcanary-variable-smoke-telemetry-service | Out-Null
$null=Await-Fixture {Invoke-Fixture 18084 "/actuator/health"} {param($value) $value.status -eq "UP"} "restored telemetry"
$null=Await-Fixture {Sql "select status from variable_pending_events where event_id='$parkedId'"} {param($value) $value -eq "PROCESSED"} "parked pinned event recovery"
Require ((Sql "select count(*) from variable_measurement_lineage where source_event_id='$parkedId' and variable_id='$variable' and revision=2 and status='OK'") -eq "1") "Recovered event lost its pinned revision"
@{status="passed";independentReplicaProjections=$true;sharedDeduplication=$true;primaryFailover=$true;disabledProcessingParked=$true;restoredPinnedEvent=$true;recordedAt=[DateTime]::UtcNow.ToString("o")} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "artifacts/device-variable-replica-summary.json")
Write-Output "Two Docker ingestion replicas, shared deduplication, failover and durable processing rollback/recovery passed."
