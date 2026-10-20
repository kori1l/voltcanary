param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [switch]$ExerciseOutage,
    [ValidateSet("ALL", "tram", "bus")][string]$Mode = "ALL"
)
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot/..").Path
$privateConfig = Get-Content (Join-Path $repoRoot "docker-data/hsl/config.json") -Raw | ConvertFrom-Json
if ($privateConfig.ownerEmail -ne $Email) { throw "Wrong verification owner" }
$bindings = @($privateConfig.vehicles | Where-Object { $Mode -eq "ALL" -or $_.mode -eq $Mode -or (!$_.mode -and $Mode -eq "tram") })
if (!$bindings.Count) { throw "No configured vehicles for selected mode" }
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/auth/login" -ContentType application/json -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$composeArgs = @("compose", "-f", (Join-Path $repoRoot "docker-compose.yml"), "-f", (Join-Path $repoRoot "deploy/docker-compose/hsl.yml"), "--profile", "hsl")
function Read-HslSnapshot {
    foreach ($binding in $bindings) {
        $point = Invoke-RestMethod -Uri "http://localhost:8080/telemetry/devices/$($binding.deviceId)/positions/latest?sourceId=$($binding.sourceId)" -Headers $headers
        [pscustomobject]@{ key = $binding.key; freshness = $point.freshness; position = $point.position }
    }
}
$first = @(Read-HslSnapshot)
if (@($first | Where-Object { $_.freshness -ne "FRESH" }).Count) { throw "Some selected vehicles lack fresh data" }
Start-Sleep -Seconds 12
$second = @(Read-HslSnapshot)
foreach ($item in $second) {
    $before = $first | Where-Object { $_.key -eq $item.key }
    if ([datetimeoffset]$item.position.recordedAt -le [datetimeoffset]$before.position.recordedAt) { throw "Observation timestamp did not advance: $($item.key)" }
    if ($null -ne $item.position.ignition -or $null -ne $item.position.fuelPercent -or $null -ne $item.position.odometerKm) { throw "Fabricated optional vehicle metric" }
}
$outagePassed = $null
if ($ExerciseOutage) {
    try {
        & docker @composeArgs stop hsl-connector
        if ($LASTEXITCODE -ne 0) { throw "Could not stop connector" }
        Start-Sleep -Seconds 37
        $paused = @(Read-HslSnapshot)
        if (@($paused | Where-Object { $_.freshness -ne "STALE" }).Count) { throw "Missing stale evidence during stopped connector" }
        $outagePassed = $true
    } finally {
        & docker @composeArgs start hsl-connector
        if ($LASTEXITCODE -ne 0) { throw "Could not restore connector" }
    }
    Start-Sleep -Seconds 12
    $restored = @(Read-HslSnapshot)
    if (@($restored | Where-Object { $_.freshness -ne "FRESH" }).Count) { throw "Freshness did not recover after restart" }
}
$summary = @{ checkedAt = [datetimeoffset]::UtcNow.ToString("o"); advanced = $true; outagePassed = $outagePassed; vehicles = $second }
$null = New-Item -ItemType Directory -Force (Join-Path $repoRoot "artifacts")
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $repoRoot "artifacts/hsl-live-summary.json") -Encoding utf8
$summary | ConvertTo-Json -Depth 6
