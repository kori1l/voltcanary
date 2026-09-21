param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory,

    [string] $TelemetryBaseUrl = "http://localhost:8084",
    [string] $InternalServiceToken = "dev-internal-token",
    [ValidateRange(0.001, 100)]
    [double] $SamplePercent = 1,
    [ValidateRange(1, 10000)]
    [int] $MaxSamples = 100,
    [int] $PollingIntervalMillis = 250,
    [int] $TimeoutMillis = 30000,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$written = Invoke-CapacityVisibilityObservation `
    -RunDirectory $RunDirectory `
    -TelemetryBaseUrl $TelemetryBaseUrl `
    -InternalServiceToken $InternalServiceToken `
    -SamplePercent $SamplePercent `
    -MaxSamples $MaxSamples `
    -PollingIntervalMillis $PollingIntervalMillis `
    -TimeoutMillis $TimeoutMillis

Write-Host "Visibility observation $($written.status): $($written.visibilityPath)"
