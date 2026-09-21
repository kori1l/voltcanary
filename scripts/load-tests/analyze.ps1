param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

$summaryPath = Join-Path $RunDirectory "summary.json"
if (-not (Test-Path -LiteralPath $summaryPath)) {
    throw "summary.json not found in run directory: $RunDirectory"
}

$updated = Update-CapacityRunReport -RunDirectory $RunDirectory
$summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json

Write-Host "Run: $($summary.runId)"
Write-Host "Profile: $($summary.profile)"
Write-Host "Phase: $($summary.phase)"
Write-Host "Status: $($summary.status)"
Write-Host "Overall evidence status: $($summary.overallEvidenceStatus)"
Write-Host "Scheduled readings: $($summary.workload.scheduledReadings)"
Write-Host "Expected numeric values: $($summary.workload.expectedNumericValues)"
Write-Host "Report: $($updated.reportPath)"

foreach ($property in $summary.verdicts.PSObject.Properties) {
    Write-Host "$($property.Name): $($property.Value)"
}

if ($null -ne $summary.generatorValidity) {
    Write-Host "Generator validity: $($summary.generatorValidity.status)"
    foreach ($finding in @($summary.generatorValidity.findings)) {
        Write-Host "GENERATOR $($finding.status): $($finding.check) - $($finding.detail)"
    }
}

if ($summary.blockers.Count -gt 0) {
    $summary.blockers | ForEach-Object { Write-Host "BLOCKED: $_" }
}

if ($summary.status -eq "BLOCKED") {
    exit 2
}
