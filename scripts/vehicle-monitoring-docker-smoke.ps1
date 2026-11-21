param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [string]$FixturePath='artifacts/vehicle-gps-resumed-smoke.log',
    [Parameter(Mandatory=$true)][string]$Password,
    [string]$SummaryPath='artifacts/vehicle-monitoring-docker-summary.json'
)
$ErrorActionPreference='Stop'
function Api($Method,$Path,$Body,$Headers) {
    $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true;TimeoutSec=30}
    if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 30))}
    $response=Invoke-WebRequest @arguments
    if($response.StatusCode -ne 204 -and $response.RawContentStream.Length){return ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())|ConvertFrom-Json)}
}
function Check($Name,$Condition){if(!$Condition){throw $Name};$script:checks+=@{name=$Name;passed=$true};Write-Host "PASSED: $Name"}
function Reject($Name,$Method,$Path,$Body,$Headers,$Expected){
    $status=200;try{$null=Api $Method $Path $Body $Headers}catch{if(!$_.Exception.Response){throw};$status=[int]$_.Exception.Response.StatusCode}
    Check $Name ($status -in $Expected)
}
$a=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
$f=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8|ConvertFrom-Json
$login=Api POST '/auth/login' @{email=$a.email;password=$Password} @{}
$owner=@{Authorization="Bearer $($login.accessToken)"}
$id=([guid]$f.deviceId).ToString();$source=[uri]::EscapeDataString($f.sourceId)
$checks=@();$otherDevice=$null;$emptyDevice=$null
try {
    $config=Api GET "/simulation/devices/$id/config" $null $owner
    Check 'custom route and vehicle metrics were saved explicitly' ($config.yamlConfig -match 'waypoints:' -and $config.yamlConfig -match 'vehicleMetrics: true' -and $config.hasSourceToken)
    $run=Api GET "/simulation/devices/$id/status" $null $owner
    Check 'custom route is running after explicit browser restart' ($run.status -eq 'RUNNING')
    $email="monitoring-boundary-$([guid]::NewGuid())@example.test"
    $null=Api POST '/auth/register' @{email=$email;password='Monitoring-boundary-2026!';displayName='Disposable monitoring access fixture'} @{}
    $otherLogin=Api POST '/auth/login' @{email=$email;password='Monitoring-boundary-2026!'} @{}
    $foreign=@{Authorization="Bearer $($otherLogin.accessToken)"}
    $null=Api GET '/accounts/current' $null $foreign
    $otherDevice=Api POST '/devices' @{name='Foreign monitoring fixture';type='VEHICLE'} $foreign
    $otherSource=Api POST "/devices/$($otherDevice.id)/data-sources" @{sourceType='SENSOR';protocol='REST';externalId="monitoring-foreign-$([guid]::NewGuid())"} $foreign
    $emptyDevice=Api POST '/devices' @{name='Disposable no-data vehicle';type='VEHICLE'} $owner
    $emptySource=Api POST "/devices/$($emptyDevice.id)/data-sources" @{sourceType='SENSOR';protocol='REST';externalId="monitoring-empty-$([guid]::NewGuid())"} $owner
    $targets=@(@{deviceId=$id;sourceId=$f.sourceId},@{deviceId=$emptyDevice.id;sourceId=$emptySource.externalId})
    $fleet=@(Api POST '/telemetry/vehicles/positions' @{targets=$targets} $owner)
    $empty=$fleet|Where-Object deviceId -eq $emptyDevice.id
    Check 'fleet batch distinguishes accepted position from no-data' ($fleet.Count -eq 2 -and $empty.freshness -eq 'NO_DATA' -and $null -eq $empty.position -and $null -ne ($fleet|Where-Object deviceId -eq $id).position)
    $mixed=@{targets=@(@{deviceId=$id;sourceId=$f.sourceId},@{deviceId=$otherDevice.id;sourceId=$otherSource.externalId})}
    Reject 'mixed owner fleet is denied as a whole' POST '/telemetry/vehicles/positions' $mixed $owner @(403)
    Reject 'fleet rejects more than 100 targets' POST '/telemetry/vehicles/positions' @{targets=@(1..101|ForEach-Object {@{deviceId=$id;sourceId=$f.sourceId}})} $owner @(400)
    Reject 'fleet rejects an empty selection' POST '/telemetry/vehicles/positions' @{targets=@()} $owner @(400)
    $settingsPath="/telemetry/devices/$id/vehicle-monitoring"
    $zone=@{id=[guid]::NewGuid().ToString();name='Acceptance depot';latitude=44.8125;longitude=20.4612;radiusMetres=10}
    $settings=@{speedLimitKmh=20;idleMinutes=1;zones=@($zone)}
    $baseSettings=Api GET "$settingsPath/snapshot" $null $owner
    $monitoringReview=Api POST "$settingsPath/reviews" @{expectedRevision=$baseSettings.revision;idempotencyKey=[guid]::NewGuid().ToString();settings=$settings} $owner
    $null=Api POST "$settingsPath/reviews/$($monitoringReview.id)/confirm" @{digest=$monitoringReview.digest;idempotencyKey=$monitoringReview.idempotencyKey} $owner
    $saved=Api GET $settingsPath $null $owner
    Check 'owner zone and monitoring settings persist' ($saved.speedLimitKmh -eq 20 -and $saved.idleMinutes -eq 1 -and $saved.zones[0].id -eq $zone.id)
    Reject 'foreign owner settings read denied' GET $settingsPath $null $foreign @(403)
    Reject 'foreign settings mutation denied' PUT $settingsPath $settings $foreign @(403)
    Reject 'fractional idle minutes rejected without truncation' PUT $settingsPath @{speedLimitKmh=20;idleMinutes=1.5;zones=@()} $owner @(400)
    Reject 'unknown authority field rejected' PUT $settingsPath @{speedLimitKmh=20;idleMinutes=1;zones=@();ownerUserId=$a.userId} $owner @(400)
    Reject 'invalid speed limit rejected' PUT $settingsPath @{speedLimitKmh=0;idleMinutes=1;zones=@()} $owner @(400)
    $badZone=@{id=$zone.id;name='Bad latitude';latitude=91;longitude=20;radiusMetres=10}
    Reject 'invalid zone coordinates rejected' PUT $settingsPath @{speedLimitKmh=20;idleMinutes=1;zones=@($badZone)} $owner @(400)
    Reject 'duplicate zone IDs rejected' PUT $settingsPath @{speedLimitKmh=20;idleMinutes=1;zones=@($zone,$zone)} $owner @(400)
    $unchanged=Api GET $settingsPath $null $owner
    Check 'rejected settings writes preserve the last valid revision' ($unchanged.speedLimitKmh -eq 20 -and $unchanged.idleMinutes -eq 1 -and @($unchanged.zones).Count -eq 1 -and $unchanged.zones[0].id -eq $zone.id)
    $from=([DateTime]$run.startedAt).ToUniversalTime().AddMilliseconds(-1);$cutoff=[DateTime]::UtcNow
    $base="/telemetry/devices/$id/positions/recordings?sourceId=$source&from=$($from.ToString('o'))&to=$($cutoff.ToString('o'))"
    $points=@();$cursor=$null;$pages=0
    do {
        $path="$base&limit=5";if($cursor){$path+="&cursor=$([uri]::EscapeDataString($cursor))"}
        $page=Api GET $path $null $owner;$pages++
        if($cursor -and @($page.points).Count){Check "cursor boundary page $pages is exclusive" (([DateTime]$page.points[0].recordedAt) -gt ([DateTime]$cursor))}
        $points+=@($page.points);$cursor=$page.nextCursor
        if($pages -gt 1000){throw 'Unexpected recording pagination size'}
    } while($page.hasMore)
    Check 'recording snapshot has ascending unique bounded samples' ($points.Count -ge 15 -and @($points|Group-Object recordedAt|Where-Object Count -gt 1).Count -eq 0 -and @($points|Where-Object {([DateTime]$_.recordedAt).ToUniversalTime() -gt $cutoff}).Count -eq 0)
    Check 'scheduled dwell preserves measured zero speed and ignition' (@($points|Where-Object {$_.speedKmh -eq 0 -and $null -ne $_.ignition -and $_.ignition -eq 0}).Count -gt 0)
    Check 'movement preserves optional fuel and odometer evidence' (@($points|Where-Object {$_.speedKmh -gt 3 -and $_.ignition -eq 1 -and $null -ne $_.fuelPercent -and $null -ne $_.odometerKm}).Count -gt 0 -and $points[-1].odometerKm -ge $points[0].odometerKm -and $points[-1].fuelPercent -le $points[0].fuelPercent)
    Reject 'recordings deny foreign source access' GET "$base&limit=5" $null $foreign @(403)
    Reject 'recordings reject oversized pages' GET "$base&limit=2001" $null $owner @(400)
    Reject 'recordings reject cursor at immutable cutoff' GET "$base&limit=5&cursor=$($cutoff.ToString('o'))" $null $owner @(400)
    Reject 'recordings reject ranges above 31 days' GET "/telemetry/devices/$id/positions/recordings?sourceId=$source&from=$($cutoff.AddDays(-32).ToString('o'))&to=$($cutoff.ToString('o'))" $null $owner @(400)
    $points|ConvertTo-Json -Depth 12|Set-Content artifacts/vehicle-monitoring-recording.json -Encoding utf8
    $evidence=@{status='PASSED';checks=$checks;checkCount=$checks.Count;deviceId=$id;sourceId=$f.sourceId;runId=$run.runId;configVersion=$config.yamlVersion;recordingCount=$points.Count;pages=$pages;snapshotTo=$cutoff.ToString('o');zoneId=$zone.id;demoLeftRunning=$true;recordedAt=[DateTime]::UtcNow.ToString('o')}
} finally {
    if($emptyDevice){$null=Api POST "/devices/$($emptyDevice.id)/decommission" $null $owner}
    if($otherDevice){$null=Api POST "/devices/$($otherDevice.id)/decommission" $null $foreign}
}
$evidence.disposableDevicesCleaned=$true
$evidence|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host 'PASSED: monitoring API, custom-route evidence, cursor boundaries and scoped cleanup'
