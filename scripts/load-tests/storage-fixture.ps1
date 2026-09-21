param(
    [ValidateSet("current-5", "resume-10")]
    [string] $Profile = "current-5",

    [ValidateSet("import-api", "storage-only-sql")]
    [string] $Mode = "storage-only-sql",

    [string] $RunId = "",
    [string] $RunDirectory = "",
    [string] $OutputRoot = "",
    [int] $TotalDays = 30,
    [int] $WindowHours = 6,
    [int64] $PilotValues = 1000000,
    [int64] $MeasuredPilotBytes = 0,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$plan = New-CapacityStorageFixturePlan `
    -ProfileName $Profile `
    -Mode $Mode `
    -RunId $RunId `
    -TotalDays $TotalDays `
    -WindowHours $WindowHours `
    -PilotValues $PilotValues `
    -MeasuredPilotBytes $MeasuredPilotBytes

if ($RunDirectory.Trim().Length -eq 0) {
    $preflight = New-CapacityPreflight -ProfileName $Profile -PhaseName "daily" -RunId $plan.runId
    $writeArgs = @{ Preflight = $preflight }
    if ($OutputRoot.Trim().Length -gt 0) {
        $writeArgs.OutputRoot = $OutputRoot
    }
    $evidence = Write-CapacityEvidence @writeArgs
    $RunDirectory = $evidence.runDirectory
}

$written = Write-CapacityStorageFixturePlan -Plan $plan -RunDirectory $RunDirectory
$checkpoint = Test-CapacityStorageFixtureCheckpoint -Plan $plan

Write-Host "Storage fixture $($plan.status): $($written.storageFixturePath)"
Write-Host "Mode: $($plan.mode)"
if ($plan.status -ne "BLOCKED") {
    Write-Host "Days: $($plan.targetDistribution.days)"
    Write-Host "Windows: $($plan.targetDistribution.windowCount)"
    Write-Host "Expected readings: $($plan.targetDistribution.totalReadings)"
    Write-Host "Expected numeric values: $($plan.targetDistribution.totalNumericValues)"
    Write-Host "Disk estimate GiB: $($plan.diskEstimate.estimatedGiB) ($($plan.diskEstimate.status))"
    Write-Host "Checkpoint validation: $($checkpoint.status)"
}
foreach ($blocker in @($plan.blockers | Where-Object { $null -ne $_ -and $_.ToString().Trim().Length -gt 0 })) {
    Write-Host "BLOCKED: $blocker"
}

if ($plan.status -eq "BLOCKED") {
    exit 2
}
