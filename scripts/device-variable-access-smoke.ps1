$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$compose = Join-Path $root "deploy/docker-compose/device-variables-smoke.yml"
$fixture = Get-Content -LiteralPath (Join-Path $root "artifacts/device-variables-smoke-summary.json") -Raw | ConvertFrom-Json
$recovery = Get-Content -LiteralPath (Join-Path $root "artifacts/device-variable-recovery-summary.json") -Raw | ConvertFrom-Json
if (-not $fixture.dockerServices) {throw "Requires the isolated Docker variable fixture"}
function Request([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $arguments=@{Uri="http://localhost:18080$Path";Method=$Method;Headers=$Headers;TimeoutSec=10}
    if($null -ne $Body) {$arguments.ContentType="application/json";$arguments.Body=ConvertTo-Json -InputObject $Body -Depth 20 -Compress}
    Invoke-RestMethod @arguments
}
function Denied([string]$Method,[string]$Path,$Body,[hashtable]$Headers,[int[]]$Allowed = @(403,404)) {
    try {$null=Request $Method $Path $Body $Headers;throw "Unauthorized fixture request succeeded: $Path"}
    catch {
        $status=[int]$_.Exception.Response.StatusCode
        if($status -notin $Allowed) {throw}
    }
}
function Scalar([string]$Service,[string]$Database,[string]$Text) {
    $value=& docker compose -f $compose exec -T $Service psql -U variable -d $Database -tAc $Text
    if($LASTEXITCODE -ne 0) {throw "Fixture SQL failed"}
    return ("$value").Trim()
}
$owner = Request "POST" "/auth/login" @{email=$fixture.email;password="Variable-smoke-2026!"}
$ownerHeaders=@{Authorization=("Bearer "+$owner.accessToken)}
$foreignEmail="variable-foreign-"+[guid]::NewGuid().ToString()+"@example.test"
$null=Request "POST" "/auth/register" @{email=$foreignEmail;password="Variable-smoke-2026!"}
$foreign=Request "POST" "/auth/login" @{email=$foreignEmail;password="Variable-smoke-2026!"}
$foreignHeaders=@{Authorization=("Bearer "+$foreign.accessToken);"X-Workspace-Id"=[guid]::NewGuid().ToString();"X-Account-Id"=$fixture.accountId;"X-User-Role"="ADMIN"}
$device=([guid]$fixture.deviceIds[0]).ToString();$source=([guid]$fixture.sourceIds[0]).ToString()
$otherSource=([guid]$fixture.sourceIds[1]).ToString();$variable=([guid]$fixture.variableId).ToString()
$dashboard=([guid]$recovery.dashboardId).ToString();$account=([guid]$fixture.accountId).ToString()
Denied "GET" "/device-types/FRIDGE/variables/$variable" $null $foreignHeaders
Denied "GET" "/devices/$device/data-sources/$source/variable-bindings" $null $foreignHeaders
Denied "GET" "/telemetry/devices/$device/variables" $null $foreignHeaders
Denied "GET" "/telemetry/devices/$device/latest?variableId=$variable&sourceId=$otherSource" $null $ownerHeaders
$measurementsBefore=Scalar "timescale" "telemetry" "select count(*) from telemetry.measurements"
$jobsBefore=Scalar "postgres" "analytics" "select count(*) from analytics_jobs"
$query=Request "POST" "/dashboards/$dashboard/query" @{} $ownerHeaders
if($query.panels[0].status -ne "ready") {throw "Owner variable dashboard is not ready"}
$hash=$query.panels[0].contextHash
$snapshotPath="/dashboards/$dashboard/query-snapshots/$hash/panels/temperature"
$null=Request "GET" $snapshotPath $null $ownerHeaders
Denied "POST" "/dashboards/$dashboard/query" @{} $foreignHeaders
Denied "GET" $snapshotPath $null $foreignHeaders @(403,404,410)
try {
    $null=Scalar "postgres" "device" "update accounts set status='DISABLED' where id='$account'"
    Denied "GET" $snapshotPath $null $ownerHeaders @(403,404,410)
    Denied "GET" "/telemetry/devices/$device/variables" $null $ownerHeaders
} finally {$null=Scalar "postgres" "device" "update accounts set status='ACTIVE' where id='$account'"}
if((Scalar "timescale" "telemetry" "select count(*) from telemetry.measurements") -ne $measurementsBefore) {throw "Dashboard reads changed telemetry rows"}
if((Scalar "postgres" "analytics" "select count(*) from analytics_jobs") -ne $jobsBefore) {throw "Dashboard reads created analytics jobs"}
@{status="passed";foreignAndForgedWorkspaceDenied=$true;sourceSubstitutionDenied=$true;cachedSnapshotRevocation=$true;graphReadsAreSideEffectFree=$true;recordedAt=[DateTime]::UtcNow.ToString("o")} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "artifacts/device-variable-access-summary.json")
Write-Output "Foreign/forged-workspace access, source substitution and cached snapshot revocation denied; graph reads have no telemetry/job writes."
