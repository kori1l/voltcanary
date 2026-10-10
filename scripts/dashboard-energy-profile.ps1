param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$SummaryPath = 'artifacts/dashboard-energy-profile.json',
    [string]$FixtureEmail = '',
    [string]$FixturePassword = ''
)
$ErrorActionPreference = 'Stop'
if ($Gateway -notmatch '^http://(localhost|127\.0\.0\.1):[0-9]+$') { throw 'This synthetic profile is local-only.' }
if (!$FixtureEmail) { $FixtureEmail = "dashboard-energy-$([Guid]::NewGuid().ToString('N'))@example.invalid" }
if ($FixtureEmail -notmatch '^dashboard-energy-[A-Za-z0-9-]+@example\.invalid$') { throw 'Use a reserved isolated fixture identity.' }
if (!$FixturePassword) { $FixturePassword = "Vc9!$([Guid]::NewGuid().ToString('N'))" }
Add-Type -AssemblyName System.Net.Http
$taskClient = [System.Net.Http.HttpClient]::new()
$taskClient.Timeout = [TimeSpan]::FromSeconds(30)
function Invoke-EnergyApi($Method, $Path, $Body, $Headers, $Expected = 200) {
    $taskRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$Gateway$Path")
    if ($null -ne $Body) { $taskRequest.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 40 -Compress),[Text.Encoding]::UTF8,'application/json') }
    foreach ($taskKey in $Headers.Keys) { $taskRequest.Headers.Add($taskKey,[string]$Headers[$taskKey]) }
    $taskWatch = [Diagnostics.Stopwatch]::StartNew()
    $taskResponse = $taskClient.SendAsync($taskRequest).GetAwaiter().GetResult()
    try {
        $taskText = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult(); $taskWatch.Stop()
        if ([int]$taskResponse.StatusCode -ne $Expected) { throw "Energy profile $Method $Path returned $([int]$taskResponse.StatusCode)." }
        return [pscustomobject]@{ Body = ($taskText | ConvertFrom-Json); Milliseconds = $taskWatch.Elapsed.TotalMilliseconds }
    } finally { $taskResponse.Dispose(); $taskRequest.Dispose() }
}
function Invoke-EnergySql($Sql) {
    $Sql | docker compose exec -T timescaledb sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic energy fixture SQL failed.' }
}
function New-EnergyFixtureDevice($Index, $From, $Method, $Headers, $Gap = 1800, $Location = $null) {
    $taskDevice = (Invoke-EnergyApi 'POST' '/devices' @{ name="DEMO energy profile $Index"; type='FRIDGE'; locationId=$Location; metadata=@{fixture='DASHBOARD_ENERGY_LOCAL_PROFILE'} } $Headers).Body
    $taskSource = (Invoke-EnergyApi 'POST' "/devices/$($taskDevice.id)/data-sources" @{sourceType='SMART_PLUG';protocol='REST';externalId="DEMO-energy-profile-$Index"} $Headers).Body
    foreach ($taskId in @($taskDevice.id,$taskSource.id)) { if (![Guid]::TryParse($taskId,[ref]([Guid]::Empty))) { throw 'Invalid canonical fixture resource.' } }
    $taskMeter = (Invoke-EnergyApi 'PUT' "/devices/$($taskDevice.id)/energy-meter" @{ expectedRevision=0;physicalMeterId=[Guid]::NewGuid().ToString();parentPhysicalMeterId=$null;sourceId=$taskSource.id;authoritativeMethod=$Method;maxGapSeconds=$Gap;effectiveFrom=$From;counterEvents=@() } $Headers).Body
    return [pscustomobject]@{DeviceId=$taskDevice.id;SourceId=$taskSource.id;Method=$taskMeter.authoritativeMethod}
}
function New-EnergyFixtureDashboard($Name, $Ids, $From, $To, $Compare, $Headers) {
    $taskWidget = @{ id='energy';type='energy-period';typeVersion=1;title='DEMO covered energy';binding=@{mode='FIXED';deviceIds=@($Ids)};query=@{rows=20;independentMetersConfirmed=$true};presentation=@{};navigation=@{href='/devices'};payload=@{} }
    if ($Compare) { $taskWidget.query.compare=@{mode='PREVIOUS'} }
    $taskDefinition = @{schemaVersion=1;name=$Name;description='Local synthetic profile; no production, load or capacity claim.';defaultContext=@{time=@{mode='FIXED';timezone='UTC';from=$From;to=$To}};sections=@();widgets=@($taskWidget);layouts=@{}}
    $taskCreated=(Invoke-EnergyApi 'POST' '/dashboards' @{definition=$taskDefinition} (@{}+$Headers+@{'Idempotency-Key'=[Guid]::NewGuid().ToString()}) 201).Body
    $taskDraft=(Invoke-EnergyApi 'GET' "/dashboards/$($taskCreated.id)/draft" $null $Headers).Body
    $null=Invoke-EnergyApi 'POST' "/dashboards/$($taskCreated.id)/publish" @{draftEtag=$taskDraft.etag} (@{}+$Headers+@{'If-Match'='"'+$taskDraft.etag+'"';'Idempotency-Key'=[Guid]::NewGuid().ToString()})
    return $taskCreated.id
}
try {
    $taskCredentials=@{email=$FixtureEmail;password=$FixturePassword}
    $null=Invoke-EnergyApi 'POST' '/auth/register' $taskCredentials @{}
    $taskLogin=(Invoke-EnergyApi 'POST' '/auth/login' $taskCredentials @{}).Body
    $taskHeaders=@{Authorization="Bearer $($taskLogin.accessToken)"}
    $taskAccount=(Invoke-EnergyApi 'GET' '/accounts/current' $null $taskHeaders).Body.id
    if (![Guid]::TryParse($taskAccount,[ref]([Guid]::Empty))) { throw 'Invalid fixture account.' }
    $taskTo=[DateTime]::UtcNow.AddMinutes(-1); $taskTo=$taskTo.AddTicks(-($taskTo.Ticks % [TimeSpan]::TicksPerSecond))
    $taskFrom=$taskTo.AddDays(-30); $taskFromText=$taskFrom.ToString('yyyy-MM-ddTHH:mm:ssZ'); $taskToText=$taskTo.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $taskNorth=(Invoke-EnergyApi 'POST' '/locations' @{name='DEMO energy North';description='Synthetic profile location'} $taskHeaders).Body.id
    $taskSouth=(Invoke-EnergyApi 'POST' '/locations' @{name='DEMO energy South';description='Synthetic profile location'} $taskHeaders).Body.id
    $taskMeters=@(1..12 | ForEach-Object { New-EnergyFixtureDevice $_ $taskFromText $(if ($_ % 2) {'COUNTER_DELTA'} else {'POWER_INTEGRAL'}) $taskHeaders 1800 $(if ($_ % 2) {$taskNorth} else {$taskSouth}) })
    $taskIds=@($taskMeters | ForEach-Object {$_.DeviceId})
    $taskTuple=($taskMeters | ForEach-Object {"('$($_.DeviceId)'::uuid,'$($_.SourceId)'::text)"}) -join ','
    Invoke-EnergySql @"
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '$taskAccount'::uuid,d.device_id,d.source_id,t,m.metric,
CASE WHEN m.metric='energyWh' THEN 10000+extract(epoch FROM t-'$taskFromText'::timestamp)/900*250 ELSE 1000 END,
CASE WHEN m.metric='energyWh' THEN 'Wh' ELSE 'W' END,'OK',t,t
FROM (VALUES $taskTuple) d(device_id,source_id)
CROSS JOIN generate_series('$taskFromText'::timestamp,'$taskToText'::timestamp,interval '15 minutes') t
CROSS JOIN (VALUES ('energyWh'),('activePowerW')) m(metric);
"@
    $taskDashboard=New-EnergyFixtureDashboard 'DEMO energy 30-day profile' $taskIds $taskFromText $taskToText $false $taskHeaders
    $taskCold=@(); $taskPeriod=$null
    1..3 | ForEach-Object {
        $taskRead=Invoke-EnergyApi 'POST' "/dashboards/$taskDashboard/query" @{forceRefresh=$true} $taskHeaders
        $taskPanel=$taskRead.Body.panels[0]
        if ($taskPanel.status -ne 'ready') { throw "Energy profile panel returned $($taskPanel.status): $($taskPanel.errorCode)." }
        $taskPeriod=$taskPanel.data.energy
        if ([Math]::Abs($taskPeriod.energyKWh-8640) -gt 0.00001 -or $taskPeriod.coverage -ne 'COMPLETE' -or $taskPeriod.evaluatedDevices -ne 12) { throw 'Full-scope method/coverage result differs from the synthetic fixture.' }
        if (@($taskPeriod.locations).Count -ne 2 -or @($taskPeriod.locations | Where-Object { [Math]::Abs($_.energyKWh-4320) -gt 0.00001 }).Count) { throw 'Full-scope location totals differ from the fixture.' }
        $taskCold += $taskRead.Milliseconds
    }
    $taskWarm=@()
    1..10 | ForEach-Object { $taskRead=Invoke-EnergyApi 'POST' "/dashboards/$taskDashboard/query" @{} $taskHeaders; if (!$taskRead.Body.cached) {throw 'Expected renewed-authority cached snapshot.'};$taskWarm+=$taskRead.Milliseconds }
    $taskZeroFrom=$taskTo.AddHours(-4).ToString('yyyy-MM-ddTHH:mm:ssZ');$taskCurrentFrom=$taskTo.AddHours(-2).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $taskZero=New-EnergyFixtureDevice 'zero-baseline' $taskZeroFrom 'COUNTER_DELTA' $taskHeaders 3600
    Invoke-EnergySql @"
INSERT INTO telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
SELECT '$taskAccount'::uuid,'$($taskZero.DeviceId)'::uuid,'$($taskZero.SourceId)',t,'energyWh',
10000+greatest(0,extract(epoch FROM t-'$taskCurrentFrom'::timestamp))/3600*250,'Wh','OK',t,t
FROM generate_series('$taskZeroFrom'::timestamp,'$taskToText'::timestamp,interval '1 hour') t;
"@
    $taskComparison=New-EnergyFixtureDashboard 'DEMO zero-baseline energy comparison' @($taskZero.DeviceId) $taskCurrentFrom $taskToText $true $taskHeaders
    $taskCompared=(Invoke-EnergyApi 'POST' "/dashboards/$taskComparison/query" @{forceRefresh=$true} $taskHeaders).Body.panels[0]
    if ($taskCompared.status -ne 'ready' -or [Math]::Abs($taskCompared.data.energy.energyKWh-0.5) -gt 0.000001 -or $taskCompared.data.comparison.difference -ne 0.5 -or $null -ne $taskCompared.data.comparison.percent -or $taskCompared.data.comparison.reason -ne 'zero_baseline') {throw 'Zero-baseline comparison failed.'}
    $taskOrdered=@($taskWarm | Sort-Object);$taskP95=$taskOrdered[[Math]::Ceiling($taskOrdered.Count*0.95)-1]
    $taskSummary=@{checkedAt=[DateTime]::UtcNow.ToString('o');environment='LOCAL_COMPOSE_SYNTHETIC_OWNER_QUERY';devices=12;intervalDays=30;rawSamples=69144;expectedKWh=8640;coveredKWh=$taskPeriod.energyKWh;coverage=$taskPeriod.coverage;methods=@('COUNTER_DELTA','POWER_INTEGRAL');coldMilliseconds=$taskCold;warmMilliseconds=$taskWarm;warmP95Milliseconds=$taskP95;zeroBaseline=@{absoluteDifference=$taskCompared.data.comparison.difference;percent=$taskCompared.data.comparison.percent;reason=$taskCompared.data.comparison.reason};dashboardId=$taskDashboard;comparisonDashboardId=$taskComparison;sampleLimit=500000;maximumFrameDays=31;boundaryReadMarginDays=1;retention='Persisted telemetry retention unchanged; missing boundary samples reduce coverage';capacityClaim='NONE';credentials='PROCESS_MEMORY_ONLY'}
    $taskSummary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $SummaryPath
    $taskSummary | ConvertTo-Json -Depth 10
} finally { $taskClient.Dispose() }
