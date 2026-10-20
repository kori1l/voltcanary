param([string]$FixturePath = "artifacts/device-variables-smoke-summary.json", [switch]$UseRestartedService)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$compose = Join-Path $root "deploy/docker-compose/device-variables-smoke.yml"
$fixture = Get-Content -LiteralPath (Join-Path $root $FixturePath) -Raw | ConvertFrom-Json
if (-not $fixture.dockerServices) { throw "Requires the running isolated -DockerServices fixture" }
$variable = ([guid]$fixture.variableId).ToString()
$firstDevice = ([guid]$fixture.deviceIds[0]).ToString()
$secondDevice = ([guid]$fixture.deviceIds[1]).ToString()
$firstSource = ([guid]$fixture.sourceIds[0]).ToString()
$secondSource = ([guid]$fixture.sourceIds[1]).ToString()
function Request([int]$Port, [string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = $script:headers) {
    $arguments = @{Uri="http://localhost:$Port$Path";Method=$Method;Headers=$Headers;TimeoutSec=10}
    if ($null -ne $Body) { $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 30 -Compress; $arguments.ContentType="application/json" }
    Invoke-RestMethod @arguments
}
function Check([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Await([scriptblock]$Action, [scriptblock]$Accept, [string]$Description) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try { $value = & $Action; if (& $Accept $value) { return $value } } catch { $lastFailure=$_.Exception.Message }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. $lastFailure"
}
function Scalar([string]$Database, [string]$Sql) {
    $service = if ($Database -eq "telemetry") {"timescale"} else {"postgres"}
    $result = & docker compose -f $compose exec -T $service psql -U variable -d $Database -tAc $Sql
    if ($LASTEXITCODE -ne 0) { throw "Fixture SQL failed" }
    return ("$result").Trim()
}
$session = Request 18081 "POST" "/auth/login" @{email=$fixture.email;password="Variable-smoke-2026!"} @{}
$headers = @{Authorization=("Bearer " + $session.accessToken);"X-User-Id"=$fixture.ownerId;"X-User-Role"="USER";"X-User-Email"=$fixture.email}
$source = Request 18082 "GET" "/devices/$firstDevice/data-sources/$firstSource"
$credential = Request 18082 "POST" "/devices/$firstDevice/data-sources/$firstSource/token" @{}
$sourceHeaders = @{Authorization=("Bearer " + $credential.token)}
$saved = (Scalar "telemetry" "select json_build_object('eventId',source_event_id,'timestamp',recorded_at,'receivedAt',received_at,'generation',bundle_generation,'digest',bundle_digest,'value',(input_snapshot_json::json->>'x')::double precision)::text from variable_measurement_lineage where variable_id='$variable' and data_source_id='$firstSource' and revision=1 and status='OK' order by recorded_at limit 1") | ConvertFrom-Json
Check ($null -ne $saved) "Original source event missing"
$original = [pscustomobject]@{eventId=$saved.eventId;deviceId=$firstDevice;sourceId=$source.externalId;timestamp=$saved.timestamp;receivedAt=$saved.receivedAt;eventVersion="v1";correlationId=$null;producer="ingestion-service";accountId=$fixture.accountId;occurredAt=$saved.timestamp;metrics=@{configuredFields=@{sensorTemperatureRaw=@{value=$saved.value;type="number";label="Raw temperature";unit="C";display=$true}}};processingContext=@{dataSourceId=$firstSource;generation=$saved.generation;digest=$saved.digest;inputSchemaVersion="v1";historicalImport=$false}}
if (-not $UseRestartedService) {
    & docker restart voltcanary-variable-smoke-telemetry-service | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Telemetry restart failed" }
}
$null = Await {Request 18084 "GET" "/actuator/health"} {param($value) $value.status -eq "UP"} "telemetry restart"
$rawJson = ConvertTo-Json -InputObject $original -Depth 30 -Compress
$rawJson | & docker compose -f $compose exec -T redpanda rpk topic produce telemetry.raw.v1 -X brokers=localhost:9093 -k $firstDevice | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Kafka replay failed" }
$current = Request 18082 "GET" "/device-types/FRIDGE/variables/$variable"
$expression = @{op="SUBTRACT";left=@{op="MULTIPLY";left=@{op="INPUT";alias="x"};right=@{op="CONSTANT";value=0.1}};right=@{op="CONSTANT";value=1.8}}
$draft = @{key=$current.definition.key;label=$current.definition.label;origin="DERIVED";unit="C";inputs=@{x=@{unit="C"}};expression=$expression;expectedVersion=$current.definition.version}
if (-not ($current.revisions | Where-Object {$_.revision -eq 2})) {
    $current = Request 18082 "POST" "/device-types/FRIDGE/variables/$variable/revisions" $draft
}
if (($current.revisions | Where-Object {$_.revision -eq 2}).state -ne "PUBLISHED") {
    $null = Request 18082 "POST" "/device-types/FRIDGE/variables/$variable/publish" @{expectedVersion=$current.definition.version;revision=2;makeDefault=$false}
}
$sourceBindings = Request 18082 "GET" "/devices/$firstDevice/data-sources/$firstSource/variable-bindings"
$currentBinding = $sourceBindings | Where-Object {$_.variableId -eq $variable}
if ($currentBinding.revision -ne 2) {
    $null = Request 18082 "PUT" "/devices/$firstDevice/data-sources/$firstSource/variable-bindings" @{variableId=$variable;revision=2;expectedVersion=$currentBinding.version;inputPaths=@{x="sensorTemperatureRaw"};enabled=$true}
}
$newest = Await {
    $reading = @{deviceId=$firstDevice;sourceId=$source.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=235}}
    $null = Request 18083 "POST" "/ingestion/readings" $reading $sourceHeaders
    Request 18084 "GET" "/telemetry/devices/$firstDevice/latest?variableId=$variable&sourceId=$firstSource"
} {param($value) $value.points[0].revision -eq 2 -and [Math]::Abs($value.points[0].value - 21.7) -lt 0.000001} "new pinned revision"
$replayRows = Scalar "telemetry" ("select count(*) from variable_measurement_lineage where source_event_id='" + ([guid]$original.eventId).ToString() + "'")
Check ($replayRows -eq "1") "Restart replay duplicated original lineage"
$original.eventId = [guid]::NewGuid().ToString()
$original.timestamp = ([DateTime]::Parse($original.timestamp).ToUniversalTime().AddSeconds(-10)).ToString("o")
$original.occurredAt = $original.timestamp
(ConvertTo-Json -InputObject $original -Depth 30 -Compress) | & docker compose -f $compose exec -T redpanda rpk topic produce telemetry.raw.v1 -X brokers=localhost:9093 -k $firstDevice | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Delayed historical generation delivery failed" }
$null = Await {Scalar "telemetry" ("select revision from variable_measurement_lineage where source_event_id='" + ([guid]$original.eventId).ToString() + "'")} {param($value) $value -eq "1"} "historical pinned generation"
$latest = Request 18084 "GET" "/telemetry/devices/$firstDevice/latest?variableId=$variable&sourceId=$firstSource"
Check ($latest.points[0].revision -eq 2) "Late old generation overwrote latest"

# An import with a current timestamp isolates eligibility from age suppression.
$existingMaintenance = Request 18082 "GET" "/devices/$secondDevice/maintenance-windows"
foreach ($window in $existingMaintenance) {
    if ($window.title -eq "Variable fixture maintenance" -and $null -eq $window.cancelledAt) {
        $null = Request 18082 "POST" ("/devices/$secondDevice/maintenance-windows/" + $window.id + "/cancel") @{reason="Isolated fixture rerun"}
    }
}
$null = Await {Scalar "analytics" "select count(*) from maintenance_windows_read_model where device_id='$secondDevice' and title='Variable fixture maintenance' and cancelled_at is null"} {param($value) [int]$value -eq 0} "previous maintenance cancellation"
$second = Request 18082 "GET" "/devices/$secondDevice/data-sources/$secondSource"
$secondCredential = Request 18082 "POST" "/devices/$secondDevice/data-sources/$secondSource/token" @{}
$secondHeaders = @{Authorization=("Bearer " + $secondCredential.token)}
$metric = "var_" + $variable.Replace("-", "")
$ruleHeaders = $headers + @{"X-Account-Id"=$fixture.accountId}
$rule = Request 18086 "POST" "/alert-rules" @{name=("Import exclusion fixture " + [guid]::NewGuid().ToString());ruleType="THRESHOLD";enabled=$true;scopeType="DEVICE";scopeId=$secondDevice;metric=$metric;conditionType="ABOVE_THRESHOLD";threshold=24;thresholdUnit="C";severity="WARNING";config=@{variable=@{variableId=$variable;dataSourceId=$secondSource;unit="C";maxLiveAgeSeconds=600}}} $ruleHeaders
$import = @{deviceId=$secondDevice;sourceId=$second.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=255}}
$null = Await {Request 18083 "POST" "/ingestion/imports/readings" $import $secondHeaders} {param($value) $value.acceptedCount -eq 1} "fresh historical import"
$null = Await {Scalar "telemetry" "select count(*) from variable_measurement_lineage where data_source_id='$secondSource' and eligibility='BACKFILL'"} {param($value) [int]$value -gt 0} "backfill classification"
$normal = @{deviceId=$secondDevice;sourceId=$second.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=185}}
$null = Request 18083 "POST" "/ingestion/readings" $normal $secondHeaders
$ruleId = ([guid]$rule.id).ToString()
$null = Await {Scalar "analytics" "select count(*) from variable_rule_evaluations where rule_id='$ruleId' and status='NO_MATCH'"} {param($value) [int]$value -gt 0} "live marker after import"
Check ((Scalar "alerts" "select count(*) from alerts where device_id='$secondDevice'") -eq "0") "Historical import created a live alert"

$maintenance = Request 18082 "POST" "/devices/$secondDevice/maintenance-windows" @{title="Variable fixture maintenance";startAt=[DateTime]::UtcNow.AddMinutes(-1).ToString("o");endAt=[DateTime]::UtcNow.AddMinutes(30).ToString("o");suppressAlerts=$true;excludeFromAnalytics=$true;excludeFromMl=$true}
$maintenanceId = ([guid]$maintenance.id).ToString()
$null = Await {Scalar "analytics" "select count(*) from maintenance_windows_read_model where maintenance_window_id='$maintenanceId'"} {param($value) [int]$value -gt 0} "maintenance projection"
$maintenanceReading = @{deviceId=$secondDevice;sourceId=$second.externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=255}}
$null = Request 18083 "POST" "/ingestion/readings" $maintenanceReading $secondHeaders
$null = Await {Scalar "analytics" "select count(*) from variable_rule_evaluations where rule_id='$ruleId' and status='MAINTENANCE'"} {param($value) [int]$value -gt 0} "maintenance variable exclusion"
Check ((Scalar "alerts" "select count(*) from alerts where device_id='$secondDevice'") -eq "0") "Maintenance created a live alert"

$selection = @{deviceId=$firstDevice;metric=$metric;variableId=$variable;dataSourceId=$firstSource}
$definition = @{schemaVersion=1;name=("Variable fixture dashboard " + [guid]::NewGuid().ToString().Substring(0,8));description="";defaultContext=@{};sections=@();layouts=@{};widgets=@(@{id="temperature";type="time-series";typeVersion=1;title="Chamber temperature";binding=@{mode="FIXED";deviceIds=@($firstDevice)};query=@{unit="C";reducer="RAW";series=@($selection)}})}
$dashboard = Request 18089 "POST" "/dashboards" @{definition=$definition} ($headers + @{"Idempotency-Key"=[guid]::NewGuid().ToString()})
$dashboardId = $dashboard.id
$dashboardDraft = Request 18089 "GET" "/dashboards/$dashboardId/draft"
$publishHeaders = $headers + @{"If-Match"=('"' + $dashboardDraft.etag + '"');"Idempotency-Key"=[guid]::NewGuid().ToString()}
$null = Request 18089 "POST" "/dashboards/$dashboardId/publish" @{draftEtag=$dashboardDraft.etag} $publishHeaders
$query = Request 18089 "POST" "/dashboards/$dashboardId/query" @{}
Check ($query.panels[0].status -eq "ready") ("Typed dashboard variable query failed: " + ($query | ConvertTo-Json -Depth 8 -Compress))
$result = @{status="passed";restartReplay=$true;oldGeneration=$true;newRevision=$true;lateLatestProtected=$true;freshBackfillExcluded=$true;maintenanceExcluded=$true;typedDashboard=$true;dashboardId=$dashboardId;recordedAt=[DateTime]::UtcNow.ToString("o")}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "artifacts/device-variable-recovery-summary.json")
Write-Output "Docker restart, replay, pinned revisions, backfill, maintenance and typed dashboard passed."
