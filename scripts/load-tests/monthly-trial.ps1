param(
    [Parameter(Mandatory = $true)] [string] $RunDirectory,
    [int] $ColdQueryRepeats = 3,
    [int] $WarmQueryRepeats = 5,
    [int] $LiveWriteSeconds = 300,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$plan = New-CapacityMonthlyTrialPlan `
    -RunDirectory $RunDirectory `
    -ColdQueryRepeats $ColdQueryRepeats `
    -WarmQueryRepeats $WarmQueryRepeats `
    -LiveWriteSeconds $LiveWriteSeconds
$written = Write-CapacityMonthlyTrialPlan -Plan $plan -RunDirectory $RunDirectory

Write-Host "Monthly query/write trial $($plan.status): $($written.monthlyTrialPath)"
Write-Host "Claim status: $($plan.monthlyClaimStatus)"
foreach ($blocker in @($plan.blockers | Where-Object { $null -ne $_ -and $_.ToString().Trim().Length -gt 0 })) {
    Write-Host "BLOCKED: $blocker"
}

if ($plan.status -eq "BLOCKED") {
    exit 2
}
