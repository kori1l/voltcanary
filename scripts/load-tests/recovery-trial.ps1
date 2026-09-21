param(
    [Parameter(Mandatory = $true)] [string] $RunDirectory,
    [string[]] $FaultTargets = @("consumer", "redis", "kafka", "database"),
    [int] $OutageSeconds = 60,
    [int] $DrainSeconds = 300,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$plan = New-CapacityRecoveryTrialPlan `
    -RunDirectory $RunDirectory `
    -FaultTargets $FaultTargets `
    -OutageSeconds $OutageSeconds `
    -DrainSeconds $DrainSeconds
$written = Write-CapacityRecoveryTrialPlan -Plan $plan -RunDirectory $RunDirectory

Write-Host "Recovery trial $($plan.status): $($written.recoveryTrialPath)"
Write-Host "Targets: $((@($plan.trials) | ForEach-Object { $_.target }) -join ', ')"
