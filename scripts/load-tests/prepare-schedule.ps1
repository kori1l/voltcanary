param(
    [ValidateSet("current-5", "resume-10")]
    [string] $Profile = "current-5",

    [ValidateSet("smoke", "baseline", "hourly", "daily", "burst", "stress", "mixed")]
    [string] $Phase = "smoke",

    [string] $RunId = "",
    [int] $TenantCount = 100,
    [int] $WorkerCount = 1,
    [int] $MaxMaterializedReadings = 0,
    [string] $OutputRoot = "",
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$prepared = New-CapacityFixtureSchedule `
    -ProfileName $Profile `
    -PhaseName $Phase `
    -RunId $RunId `
    -TenantCount $TenantCount `
    -WorkerCount $WorkerCount `
    -MaxMaterializedReadings $MaxMaterializedReadings

$writeArgs = @{ Prepared = $prepared }
if ($OutputRoot.Trim().Length -gt 0) {
    $writeArgs.OutputRoot = $OutputRoot
}
$evidence = Write-CapacityFixtureSchedule @writeArgs

Write-Host "Capacity schedule prepare $($prepared.status): $($evidence.runDirectory)"
if ($prepared.status -ne "BLOCKED") {
    Write-Host "Devices: $($prepared.fixture.deviceCount)"
    Write-Host "Workers: $($prepared.schedule.workerCount)"
    Write-Host "Scheduled readings: $($prepared.preflight.workload.scheduledReadings)"
    Write-Host "Materialized readings: $($prepared.schedule.materializedReadingCount)"
    Write-Host "Schedule complete: $($prepared.schedule.scheduleComplete)"
}

if ($prepared.preflight.blockers.Count -gt 0) {
    $prepared.preflight.blockers | ForEach-Object { Write-Host "BLOCKED: $_" }
}
if ($prepared.preflight.warnings.Count -gt 0) {
    $prepared.preflight.warnings | ForEach-Object { Write-Host "WARN: $_" }
}

if ($prepared.status -eq "BLOCKED") {
    exit 2
}
