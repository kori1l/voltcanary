param(
    [string] $RunId = "",
    [string] $OutputRoot = "",
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$manifest = New-CapacityEnvironmentManifest -RunId $RunId
$writeArgs = @{ Manifest = $manifest }
if ($OutputRoot.Trim().Length -gt 0) {
    $writeArgs.OutputRoot = $OutputRoot
}
$written = Write-CapacityEnvironmentManifest @writeArgs

Write-Host "Capacity environment manifest: $($written.environmentManifestPath)"
Write-Host "Commit: $($manifest.git.commit)"
Write-Host "Dirty entries: $($manifest.git.dirtyEntryCount)"
Write-Host "Kafka default partitions: $($manifest.kafka.defaultPartitions)"
Write-Host "Ingestion source requests/window: $($manifest.rateLimits.ingestion.sourceRequestsPerWindow)"
