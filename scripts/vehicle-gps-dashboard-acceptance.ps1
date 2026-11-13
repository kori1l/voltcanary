param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [string]$FixturePath='artifacts/vehicle-gps-resumed-smoke.log',
    [Parameter(Mandatory=$true)][string]$Password,
    [string]$SummaryPath='artifacts/vehicle-gps-dashboard-summary.json'
)
$ErrorActionPreference='Stop'
$a=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
$f=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8|ConvertFrom-Json
$login=Invoke-RestMethod "$Gateway/auth/login" -Method Post -ContentType application/json -Body (@{email=$a.email;password=$Password}|ConvertTo-Json)
$owner=@{Authorization="Bearer $($login.accessToken)"}
function Api($Method,$Path,$Body,$Extra=@{}) {
    $headers=@{Authorization=$owner.Authorization};foreach($key in $Extra.Keys){$headers[$key]=$Extra[$key]}
    $requestArguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$headers;TimeoutSec=30}
    if($null -ne $Body){$requestArguments.ContentType='application/json; charset=utf-8';$requestArguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 30))}
    Invoke-RestMethod @requestArguments
}
$copy=$null
try {
    $to=[DateTime]::UtcNow
    $limited=Api POST "/dashboards/$($f.dashboardId)/query" @{forceRefresh=$true;runtimeContext=@{time=@{mode='FIXED';from=$to.AddHours(-25).ToString('o');to=$to.ToString('o');timezone='UTC'}}}
    $map=$limited.panels|Where-Object widgetId -eq 'vehicle-map';$speed=$limited.panels|Where-Object widgetId -eq 'speed-proof'
    if($map.errorCode -ne 'LIMIT_EXCEEDED' -or $null -ne $map.data -or $speed.status -ne 'ready'){throw 'Vehicle limit did not remain isolated from the scalar panel'}
    $latest=Api GET "/telemetry/devices/$($f.deviceId)/positions/latest?sourceId=$($f.sourceId)" $null
    $cutoff=([DateTime]$latest.position.recordedAt).ToUniversalTime().AddSeconds(-10)
    $historical=Api POST "/dashboards/$($f.dashboardId)/query" @{forceRefresh=$true;runtimeContext=@{time=@{mode='FIXED';from=$cutoff.AddMinutes(-15).ToString('o');to=$cutoff.ToString('o');timezone='UTC'}}}
    $point=($historical.panels|Where-Object widgetId -eq 'vehicle-map').data.positions.latest.position
    if($null -eq $point -or ([DateTime]$point.recordedAt).ToUniversalTime() -gt $cutoff){throw 'Historical dashboard exposed a later position'}
    $copy=Api POST "/dashboards/$($f.dashboardId)/copy" @{name='Disposable GPS publication copy'} @{'Idempotency-Key'=[guid]::NewGuid().ToString()}
    $draft=Api GET "/dashboards/$($copy.id)/draft" $null
    $widget=$draft.definition.widgets|Where-Object id -eq 'vehicle-map'
    if($copy.publishedRevision -ne 0 -or $widget.query.sourceId -ne $f.sourceId -or $widget.binding.deviceIds[0] -ne $f.deviceId -or $widget.typeVersion -ne 1 -or $draft.definition.screens[0].widgetIds -notcontains 'vehicle-map' -or $draft.definition.layouts.desktop[0].h -ne 11){throw 'Copy did not preserve ordinary map binding, screen and layout'}
    $evidence=@{status='PASSED';isolatedRangeLimit=$true;historicalCutoff=$cutoff.ToString('o');historicalPosition=$point.recordedAt;copyPreserved=$true;publishedDashboardId=$f.dashboardId;recordedAt=[DateTime]::UtcNow.ToString('o')}
} finally {
    if($copy){$draft=Api GET "/dashboards/$($copy.id)/draft" $null;$null=Api POST "/dashboards/$($copy.id)/archive" $null @{'If-Match'=('"'+$draft.etag+'"')}}
}
$evidence.copyCleanupCompleted=$true
$evidence|ConvertTo-Json|Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host 'PASSED: isolated map limit, historical cutoff and ordinary copy/layout preservation'
