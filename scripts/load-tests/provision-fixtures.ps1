param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory,

    [string] $DeviceApiBaseUrl = "http://localhost:8082",
    [string[]] $IngestionBaseUrls = @(),
    [string] $BearerToken = "",
    [string] $InternalServiceToken = "dev-internal-token",
    [int] $LimitDevices = 0,
    [ValidateSet("REST", "MQTT")]
    [string] $SourceProtocol = "REST",
    [switch] $RotateSourceTokens,
    [string] $SecretOutputPath = "",
    [switch] $Cleanup,
    [switch] $DryRun,
    [int] $TimeoutSeconds = 30,
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

if ($Cleanup) {
    $result = Invoke-CapacityFixtureCleanup `
        -RunDirectory $RunDirectory `
        -DeviceApiBaseUrl $DeviceApiBaseUrl `
        -BearerToken $BearerToken `
        -DryRun:$DryRun `
        -TimeoutSeconds $TimeoutSeconds

    Write-Host "Capacity fixture cleanup $($result.status): $($result.runDirectory)"
    Write-Host "State: $($result.statePath)"
    exit 0
}

$result = Invoke-CapacityFixtureProvisioning `
    -RunDirectory $RunDirectory `
    -DeviceApiBaseUrl $DeviceApiBaseUrl `
    -IngestionBaseUrls $IngestionBaseUrls `
    -BearerToken $BearerToken `
    -InternalServiceToken $InternalServiceToken `
    -LimitDevices $LimitDevices `
    -SourceProtocol $SourceProtocol `
    -RotateSourceTokens:$RotateSourceTokens `
    -SecretOutputPath $SecretOutputPath `
    -DryRun:$DryRun `
    -TimeoutSeconds $TimeoutSeconds

Write-Host "Capacity fixture provisioning $($result.status): $($result.runDirectory)"
Write-Host "State: $($result.statePath)"
Write-Host "Summary: $($result.summaryPath)"
if ($null -ne $result.secretPath) {
    Write-Host "Secrets: $($result.secretPath)"
}
