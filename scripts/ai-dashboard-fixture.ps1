param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [Parameter(Mandatory=$true)][string]$Password,
    [string]$FixturePath='artifacts/ai-dashboard-fixture.json'
)
$ErrorActionPreference='Stop'
$account=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
function Api($Method,$Path,$Body,$Headers) {
    $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true;TimeoutSec=30}
    if ($null -ne $Body) { $arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 15)) }
    $response=Invoke-WebRequest @arguments
    if ($response.StatusCode -eq 204) { return $null }
    $text=[Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ($text.Trim()) { return ($text|ConvertFrom-Json) }
}
$login=Api POST '/auth/login' @{email=$account.email;password=$Password} @{}
$owner=@{Authorization="Bearer $($login.accessToken)"}
$fixture=$null
if (Test-Path -LiteralPath $FixturePath) {
    $candidate=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8|ConvertFrom-Json
    $device=Api GET "/devices/$($candidate.deviceId)" $null $owner
    if ($device.status -eq 'ACTIVE' -and $device.name -eq 'AI dashboard acceptance fridge') { $fixture=$candidate }
}
if (!$fixture) {
    $key="ai-dashboard-fixture-$([guid]::NewGuid())"
    $createHeaders=@{Authorization=$owner.Authorization;'Idempotency-Key'=$key}
    $device=Api POST '/devices' @{name='AI dashboard acceptance fridge';type='FRIDGE'} $createHeaders
    $fixture=[pscustomobject]@{deviceId=$device.id;sourceId=$null;externalId=$null;accountId=$account.accountId;synthetic=$true}
    $fixture|ConvertTo-Json|Set-Content -LiteralPath $FixturePath -Encoding utf8
}
if (!$fixture.sourceId) {
    $external="ai-acceptance-$([guid]::NewGuid())"
    $source=Api POST "/devices/$($fixture.deviceId)/data-sources" @{sourceType='SENSOR';protocol='REST';externalId=$external} $owner
    $fixture.sourceId=$source.id;$fixture.externalId=$external
    $fixture|ConvertTo-Json|Set-Content -LiteralPath $FixturePath -Encoding utf8
}
$credential=Api POST "/devices/$($fixture.deviceId)/data-sources/$($fixture.sourceId)/token" $null $owner
$reading=@{deviceId=$fixture.deviceId;sourceId=$fixture.externalId;timestamp=[DateTime]::UtcNow.ToString('o');metrics=@{activePowerW=0;temperatureC=4}}
$accepted=$false
for ($attempt=0;$attempt -lt 30;$attempt++) {
    try {
        $result=Api POST '/ingestion/readings' $reading @{Authorization="Bearer $($credential.token)"}
        if ($result.acceptedCount -ge 1) { $accepted=$true;break }
    } catch { }
    Start-Sleep -Seconds 2
}
Remove-Variable credential
if (!$accepted) { throw 'Synthetic source reading was not accepted; fixture IDs are saved for cleanup' }
$ready=$false
for ($attempt=0;$attempt -lt 30;$attempt++) {
    $metrics=Api GET "/telemetry/devices/$($fixture.deviceId)/metrics" $null $owner
    if (@($metrics|Where-Object {$_.name -eq 'activePowerW' -and $_.unit -eq 'W'}).Count -eq 1 -and @($metrics|Where-Object {$_.name -eq 'temperatureC' -and $_.unit -eq 'C'}).Count -eq 1) { $ready=$true;break }
    Start-Sleep -Seconds 2
}
if (!$ready) { throw 'Accepted synthetic metrics did not become queryable' }
Write-Host 'PASSED: dedicated synthetic fixture has accepted zero power and temperature; no credential persisted'
