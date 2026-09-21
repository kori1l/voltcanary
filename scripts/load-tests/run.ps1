param(
    [ValidateSet("current-5", "resume-10")]
    [string] $Profile = "current-5",

    [ValidateSet("preflight", "smoke", "baseline", "hourly", "daily", "burst", "stress", "mixed")]
    [string] $Phase = "preflight",

    [ValidateSet("rest", "mqtt")]
    [string] $Protocol = "rest",

    [ValidateSet("prepare", "preflight", "generate", "reconcile", "report", "cleanup")]
    [string] $RunnerPhase = "generate",

    [string] $RunId = "",
    [string] $OutputRoot = "",
    [int] $TenantCount = 100,
    [int] $WorkerCount = 1,
    [int] $MaxMaterializedReadings = 0,
    [int] $MaxStartedReadings = 0,
    [int] $MaxDurationSeconds = 0,
    [double] $MinFreeDiskPercent = 0,
    [double] $ObservedErrorRatePercent = 0,
    [double] $MaxErrorRatePercent = 5,
    [int] $ObservedLagGrowthSeconds = 0,
    [int] $MaxLagGrowthSeconds = 300,
    [string[]] $FaultTargets = @(),
    [ValidateSet("auto", "local", "docker")]
    [string] $K6Mode = "auto",
    [switch] $Execute,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

if ($Phase -eq "preflight") {
    $effectivePhase = "daily"
} else {
    $effectivePhase = $Phase
}

$preflight = New-CapacityPreflight -ProfileName $Profile -PhaseName $effectivePhase -RunId $RunId
if ($Phase -eq "preflight") {
    $preflight.phase = "preflight"
    $preflight.phaseDescription = "Capability and arithmetic preflight using daily claim arithmetic."
}

$writeArgs = @{ Preflight = $preflight }
if ($OutputRoot.Trim().Length -gt 0) {
    $writeArgs.OutputRoot = $OutputRoot
}
$evidence = Write-CapacityEvidence @writeArgs

Write-Host "Capacity preflight $($preflight.status): $($evidence.runDirectory)"
Write-Host "Scheduled readings: $($preflight.workload.scheduledReadings)"
Write-Host "Expected numeric values: $($preflight.workload.expectedNumericValues)"
if ($preflight.blockers.Count -gt 0) {
    $preflight.blockers | ForEach-Object { Write-Host "BLOCKED: $_" }
}
if ($preflight.warnings.Count -gt 0) {
    $preflight.warnings | ForEach-Object { Write-Host "WARN: $_" }
}

if ($preflight.status -eq "BLOCKED") {
    exit 2
}

if ($Phase -ne "preflight") {
    $scheduleArgs = @{
        ProfileName = $Profile
        PhaseName = $Phase
        RunId = $preflight.runId
        TenantCount = $TenantCount
        WorkerCount = $WorkerCount
        MaxMaterializedReadings = $MaxMaterializedReadings
    }
    $prepared = New-CapacityFixtureSchedule @scheduleArgs
    $scheduled = Write-CapacityFixtureSchedule -Prepared $prepared -OutputRoot (Split-Path -Parent $evidence.runDirectory)
    if ($Execute -and -not $prepared.schedule.scheduleComplete) {
        throw "Real execution requires the complete schedule. Set -MaxMaterializedReadings 0 or at least $($prepared.preflight.workload.scheduledReadings)."
    }
    $runner = Invoke-CapacityRunnerPhase `
        -RunDirectory $scheduled.runDirectory `
        -Phase $RunnerPhase `
        -Protocol $Protocol `
        -MaxMaterializedReadings $MaxMaterializedReadings `
        -MaxStartedReadings $MaxStartedReadings `
        -MaxDurationSeconds $MaxDurationSeconds `
        -MinFreeDiskPercent $MinFreeDiskPercent `
        -ObservedErrorRatePercent $ObservedErrorRatePercent `
        -MaxErrorRatePercent $MaxErrorRatePercent `
        -ObservedLagGrowthSeconds $ObservedLagGrowthSeconds `
        -MaxLagGrowthSeconds $MaxLagGrowthSeconds `
        -FaultTargets $FaultTargets `
        -K6Mode $K6Mode `
        -DryRun:(-not [bool] $Execute)
    Write-Host "Runner phase $RunnerPhase`: $($runner.status): $($runner.runnerSummaryPath)"
    if ($RunnerPhase -eq "generate" -and -not $Execute) {
        Write-Host "Dry-run only. Re-run with -Execute after fixture provisioning and readiness checks to collect observed generation evidence."
    }
}
