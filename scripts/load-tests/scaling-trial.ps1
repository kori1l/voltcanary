param(
    [Parameter(Mandatory = $true)] [string] $RunDirectory,
    [int[]] $ReplicaCounts = @(1, 2, 3),
    [int] $HpaSampleSeconds = 30,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$plan = New-CapacityScalingTrialPlan `
    -RunDirectory $RunDirectory `
    -ReplicaCounts $ReplicaCounts `
    -HpaSampleSeconds $HpaSampleSeconds
$written = Write-CapacityScalingTrialPlan -Plan $plan -RunDirectory $RunDirectory

Write-Host "Scaling trial $($plan.status): $($written.scalingTrialPath)"
Write-Host "Fixed-replica runs: $(@($plan.fixedReplicaRuns).Count)"
Write-Host "HPA timeline: $($plan.hpaTimeline.status)"
