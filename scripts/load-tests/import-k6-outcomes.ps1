param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory,

    [string] $OutcomePath = "",

    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

if ($OutcomePath.Trim().Length -eq 0) {
    $OutcomePath = Join-Path $RunDirectory "k6-outcomes.ndjson"
}

$generationManifestPath = Join-Path $RunDirectory "generation-manifest.json"
if (-not (Test-Path -LiteralPath $generationManifestPath)) {
    throw "generation-manifest.json not found in run directory: $RunDirectory"
}

$plan = Get-Content -Raw -LiteralPath $generationManifestPath | ConvertFrom-Json
$result = ConvertFrom-CapacityK6RestOutcomes -Plan $plan -OutcomePath $OutcomePath
$written = Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $result

Write-Host "Imported k6 outcomes: $($result.status)"
Write-Host "Generation result: $($written.generationResultPath)"
Write-Host "Scheduled readings: $($result.scheduledReadings)"
Write-Host "Started readings: $($result.startedReadings)"
Write-Host "Accepted unique readings: $($result.acceptedUniqueReadings)"
Write-Host "Duplicates: $($result.duplicates)"
Write-Host "Rejected readings: $($result.rejectedReadings)"
Write-Host "Unknown outcomes: $($result.unknownOutcomes)"
Write-Host "Dropped scheduled readings: $($result.droppedScheduledReadings)"
