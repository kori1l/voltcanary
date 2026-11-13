param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [string]$FixturePath='artifacts/vehicle-gps-resumed-smoke.log',
    [Parameter(Mandatory=$true)][string]$Password,
    [string]$SummaryPath='artifacts/vehicle-gps-acceptance-summary.json'
)
$ErrorActionPreference='Stop'
function Api($Method,$Path,$Body,$Headers) {
    $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true;TimeoutSec=30}
    if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 35))}
    $response=Invoke-WebRequest @arguments
    if($response.StatusCode -ne 204 -and $response.RawContentStream.Length){return ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())|ConvertFrom-Json)}
}
function Check($Name,$Condition){if(!$Condition){throw $Name};$script:checks+=@{name=$Name;passed=$true};Write-Host "PASSED: $Name"}
function Denied($Name,$Path,$Headers,$Statuses){
    $status=200;try{$null=Api GET $Path $null $Headers}catch{if(!$_.Exception.Response){throw};$status=[int]$_.Exception.Response.StatusCode}
    Check $Name ($status -in $Statuses)
}
$a=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
$f=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8|ConvertFrom-Json
$login=Api POST '/auth/login' @{email=$a.email;password=$Password} @{}
$owner=@{Authorization="Bearer $($login.accessToken)"}
$checks=@();$appliance=$null;$applianceStarted=$false
$id=([guid]$f.deviceId).ToString();$source=[uri]::EscapeDataString($f.sourceId)
try {
    $device=Api GET "/devices/$id" $null $owner
    Check 'VEHICLE without a required location' ($device.type -eq 'VEHICLE' -and $null -eq $device.locationId)
    $config=Api GET "/simulation/devices/$id/config" $null $owner
    Check 'GPS profile uses ordinary saved configuration' ($config.profile -eq 'VEHICLE_GPS' -and $config.protocol -eq 'HTTP')
    $history=$null
    for($i=0;$i -lt 20;$i++){
        $to=[DateTime]::UtcNow;$from=$to.AddMinutes(-15)
        $history=Api GET "/telemetry/devices/$id/positions?sourceId=$source&from=$($from.ToString('o'))&to=$($to.ToString('o'))" $null $owner
        if(@($history.points).Count -ge 3){break};Start-Sleep -Seconds 2
    }
    $points=@($history.points)
    Check 'accepted positions move at configured physical speed' ($points.Count -ge 3 -and $points[-1].speedKmh -eq 36 -and ($points[0].latitude -ne $points[-1].latitude -or $points[0].longitude -ne $points[-1].longitude))
    $cutoff=$points[-2].recordedAt
    $historical=Api GET "/telemetry/devices/$id/positions/latest?sourceId=$source&asOf=$([uri]::EscapeDataString($cutoff))" $null $owner
    Check 'historical asOf excludes later accepted points' ($historical.position.recordedAt -eq $cutoff)
    Denied 'history above 24h is rejected' "/telemetry/devices/$id/positions?sourceId=$source&from=$($to.AddHours(-25).ToString('o'))&to=$($to.ToString('o'))" $owner @(400)
    Denied 'unknown source is rejected' "/telemetry/devices/$id/positions/latest?sourceId=not-this-vehicles-source" $owner @(400,403,404)
    $email="gps-boundary-$([guid]::NewGuid())@example.test"
    $null=Api POST '/auth/register' @{email=$email;password='Gps-boundary-test-2026!';displayName='Disposable GPS access fixture'} @{}
    $other=Api POST '/auth/login' @{email=$email;password='Gps-boundary-test-2026!'} @{}
    $foreign=@{Authorization="Bearer $($other.accessToken)"}
    $null=Api GET '/accounts/current' $null $foreign
    Denied 'foreign coordinates are hidden' "/telemetry/devices/$id/positions/latest?sourceId=$source" $foreign @(403,404)
    $view=Api GET "/dashboards/$($f.dashboardId)" $null $owner
    $batch=Api POST "/dashboards/$($f.dashboardId)/query" @{forceRefresh=$true} $owner
    Check 'published map uses ordinary authorized position evidence' ($view.dashboard.publishedRevision -ge 1 -and $batch.panels[0].status -eq 'ready' -and $null -ne $batch.panels[0].data.positions.latest.position)
    $null=Api POST "/simulation/devices/$id/stop" $null $owner
    Start-Sleep -Seconds 8
    $stopped=Api GET "/telemetry/devices/$id/positions/latest?sourceId=$source" $null $owner
    $stale=$null
    for($i=0;$i -lt 25;$i++){
        $stale=Api GET "/telemetry/devices/$id/positions/latest?sourceId=$source" $null $owner
        if($stale.freshness -eq 'STALE'){break};Start-Sleep -Seconds 2
    }
    Check 'stop retains the last accepted point and becomes stale' ($stale.freshness -eq 'STALE' -and $stale.position.recordedAt -eq $stopped.position.recordedAt)
    $startAt=[DateTime]::UtcNow
    $null=Api POST "/simulation/devices/$id/start" $null $owner
    $restarted=$null
    for($i=0;$i -lt 25;$i++){
        $after=Api GET "/telemetry/devices/$id/positions?sourceId=$source&from=$($startAt.ToString('o'))&to=$([DateTime]::UtcNow.ToString('o'))" $null $owner
        if(@($after.points).Count){$restarted=$after.points[0];break};Start-Sleep -Seconds 2
    }
    Check 'explicit restart begins at the local route origin' ($null -ne $restarted -and [Math]::Abs($restarted.latitude-44.8125) -lt 0.000001 -and [Math]::Abs($restarted.longitude-20.4612) -lt 0.000001)
    $appliance=Api POST '/devices' @{name='Disposable GPS appliance regression';type='FRIDGE'} $owner
    $external="gps-appliance-$([guid]::NewGuid())"
    $sensor=Api POST "/devices/$($appliance.id)/data-sources" @{sourceType='SIMULATOR';protocol='REST';externalId=$external} $owner
    $credential=Api POST "/devices/$($appliance.id)/data-sources/$($sensor.id)/token" $null $owner
    $template=Api GET "/simulation/templates/FRIDGE?sourceId=$external" $null $owner
    $validation=Api POST "/simulation/devices/$($appliance.id)/config/validate" @{yamlConfig=$template.yamlConfig} $owner
    Check 'ordinary fridge template stays valid without GPS fields' $validation.valid
    $null=Api PUT "/simulation/devices/$($appliance.id)/config" @{dataSourceId=$sensor.id;name='Disposable fridge compatibility';profile='FRIDGE';protocol='HTTP';yamlConfig=$template.yamlConfig;sourceToken=$credential.token} $owner
    $credential=$null
    $null=Api POST "/simulation/devices/$($appliance.id)/start" $null $owner
    $applianceStarted=$true;$metrics=@()
    for($i=0;$i -lt 30;$i++){
        $metrics=@(Api GET "/telemetry/devices/$($appliance.id)/metrics" $null $owner)
        if(@($metrics|Where-Object {$_.name -eq 'activePowerW' -and $_.unit -eq 'W'}).Count){break};Start-Sleep -Seconds 2
    }
    Check 'ordinary fridge simulation reaches accepted telemetry' (@($metrics|Where-Object {$_.name -eq 'activePowerW' -and $_.unit -eq 'W'}).Count -gt 0)
    $evidence=@{status='PASSED';deviceId=$id;dashboardId=$f.dashboardId;sourceId=$f.sourceId;initialPositionCount=$points.Count;checks=$checks;checkCount=$checks.Count;demoLeftRunning=$true;recordedAt=[DateTime]::UtcNow.ToString('o')}
} finally {
    if($applianceStarted){$null=Api POST "/simulation/devices/$($appliance.id)/stop" $null $owner}
    if($appliance){$null=Api POST "/devices/$($appliance.id)/decommission" $null $owner}
}
$evidence.applianceCleanupCompleted=$true
$evidence|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host 'PASSED: GPS acceptance and appliance regression; GPS demo remains running'
