param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory,

    [Parameter(Mandatory = $true)]
    [string] $CommittedMeasurementsPath,

    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$written = Invoke-CapacityLogicalReconciliation `
    -RunDirectory $RunDirectory `
    -CommittedMeasurementsPath $CommittedMeasurementsPath

Write-Host "Logical reconciliation $($written.status): $($written.reconciliationPath)"
