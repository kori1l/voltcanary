param(
    [int] $TimeoutSeconds = 1200,
    [switch] $StartStack,
    [switch] $CiMode,
    [string] $SummaryPath = "artifacts/ml-full-verification-summary.json"
)

$ErrorActionPreference = "Stop"
if ($CiMode) { $ProgressPreference = "SilentlyContinue" }
. "$PSScriptRoot/ml-verification-fixtures.ps1"

$context = New-MlVerificationRunContext -Prefix "ml-full"
$startedAt = $context.StartedAt
$scenarioSummaries = @()

function Wait-HttpStatus {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Uri,
        [int] $ExpectedStatus = 200,
        [int] $TimeoutSeconds = 180,
        [int] $DelaySeconds = 5
    )

    Wait-MlVerificationCondition $Name {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ([int] $response.StatusCode -eq $ExpectedStatus) { return $true }
            return $false
        } catch {
            return $false
        }
    } -TimeoutSeconds $TimeoutSeconds -DelaySeconds $DelaySeconds | Out-Null
}

function Invoke-DockerResilienceChecks {
    $checks = New-Object System.Collections.Generic.List[object]

    try {
        Write-Host "[$(Get-Date -Format HH:mm:ss)] Resilience: ML-service outage and recovery"
        docker compose stop ml-service
        if ($LASTEXITCODE -ne 0) { throw "docker compose stop ml-service failed" }
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8090/health" -TimeoutSec 5 | Out-Null
            throw "ml-service health unexpectedly succeeded while service was stopped"
        } catch {
            if ($_.Exception.Message -like "*unexpectedly succeeded*") { throw }
        }
        docker compose start ml-service
        if ($LASTEXITCODE -ne 0) { throw "docker compose start ml-service failed" }
        Wait-HttpStatus -Name "ml-service recovery" -Uri "http://localhost:8090/health" -TimeoutSeconds $TimeoutSeconds
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-010"; name = "ML-service outage recovery"; status = "PASSED" })

        Write-Host "[$(Get-Date -Format HH:mm:ss)] Resilience: analytics-service restart"
        docker compose restart analytics-service
        if ($LASTEXITCODE -ne 0) { throw "docker compose restart analytics-service failed" }
        Wait-HttpStatus -Name "analytics-service restart recovery" -Uri "http://localhost:8085/actuator/health" -TimeoutSeconds $TimeoutSeconds
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-011A"; name = "analytics-service restart recovery"; status = "PASSED" })

        Write-Host "[$(Get-Date -Format HH:mm:ss)] Resilience: alert-service restart"
        docker compose restart alert-service
        if ($LASTEXITCODE -ne 0) { throw "docker compose restart alert-service failed" }
        Wait-HttpStatus -Name "alert-service restart recovery" -Uri "http://localhost:8086/actuator/health" -TimeoutSeconds $TimeoutSeconds
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-011B"; name = "alert-service restart recovery"; status = "PASSED" })

        Write-Host "[$(Get-Date -Format HH:mm:ss)] Resilience: artifact store outage and recovery"
        docker compose stop minio
        if ($LASTEXITCODE -ne 0) { throw "docker compose stop minio failed" }
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8090/health/artifacts" -TimeoutSec 5 | Out-Null
            throw "artifact health unexpectedly succeeded while MinIO was stopped"
        } catch {
            if ($_.Exception.Message -like "*unexpectedly succeeded*") { throw }
        }
        docker compose start minio
        if ($LASTEXITCODE -ne 0) { throw "docker compose start minio failed" }
        Wait-HttpStatus -Name "artifact store recovery" -Uri "http://localhost:8090/health/artifacts" -TimeoutSeconds $TimeoutSeconds
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-005"; name = "artifact outage recovery"; status = "PASSED" })

        Write-Host "[$(Get-Date -Format HH:mm:ss)] Resilience: Redpanda restart and replay recovery"
        docker compose restart redpanda
        if ($LASTEXITCODE -ne 0) { throw "docker compose restart redpanda failed" }
        Wait-HttpStatus -Name "gateway recovery after Redpanda restart" -Uri "http://localhost:8080/actuator/health" -TimeoutSeconds $TimeoutSeconds
        Wait-HttpStatus -Name "analytics recovery after Redpanda restart" -Uri "http://localhost:8085/actuator/health" -TimeoutSeconds $TimeoutSeconds
        Wait-HttpStatus -Name "alert recovery after Redpanda restart" -Uri "http://localhost:8086/actuator/health" -TimeoutSeconds $TimeoutSeconds
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-011C"; name = "Redpanda restart replay recovery"; status = "PASSED" })
    } catch {
        $checks.Add([PSCustomObject]@{ id = "ML-E2E-010"; name = "Docker resilience checks"; status = "FAILED"; failureMessage = "$($_.Exception.Message)" })
    }

    return $checks.ToArray()
}

function Invoke-SmokeScript {
    param(
        [Parameter(Mandatory = $true)] [string] $Id,
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Script,
        [Parameter(Mandatory = $true)] [string] $ScenarioSummaryPath,
        [switch] $PassStartStack
    )

    $arguments = @("-ExecutionPolicy", "Bypass", "-File", $Script, "-CiMode", "-TimeoutSeconds", "$TimeoutSeconds", "-SummaryPath", $ScenarioSummaryPath)
    if ($PassStartStack) {
        $arguments += "-StartStack"
    }
    & powershell @arguments | ForEach-Object { Write-Host $_ }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        return [PSCustomObject]@{ id = $Id; name = $Name; status = "FAILED"; summaryPath = $ScenarioSummaryPath; failureMessage = "Script exited with $exitCode" }
    }
    return [PSCustomObject]@{ id = $Id; name = $Name; status = "PASSED"; summaryPath = $ScenarioSummaryPath }
}

if ($StartStack) {
    Write-Host "[$(Get-Date -Format HH:mm:ss)] Starting Docker app and observability profiles"
    docker compose --profile app --profile observability up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed" }
}

$scenarioSummaries += Invoke-SmokeScript -Id "ML-E2E-002" -Name "failure-prediction production smoke" -Script "$PSScriptRoot/ml-failure-prediction-docker-smoke.ps1" -ScenarioSummaryPath "artifacts/ml-production-smoke-summary.json"
$scenarioSummaries += Invoke-SmokeScript -Id "ML-E2E-003" -Name "anomaly-detection docker smoke" -Script "$PSScriptRoot/ml-anomaly-detection-docker-smoke.ps1" -ScenarioSummaryPath "artifacts/ml-anomaly-smoke-summary.json"
$scenarioSummaries += Invoke-SmokeScript -Id "ML-E2E-006" -Name "alert incident docker smoke" -Script "$PSScriptRoot/ml-alert-incident-docker-smoke.ps1" -ScenarioSummaryPath "artifacts/ml-alert-incident-smoke-summary.json"
$scenarioSummaries += @(Invoke-DockerResilienceChecks)

$screenshotPaths = @(
    "artifacts/screenshots/ml-full-verification/ml-settings-desktop.png",
    "artifacts/screenshots/ml-full-verification/ml-settings-mobile.png",
    "artifacts/screenshots/ml-full-verification/alerts-desktop.png",
    "artifacts/screenshots/ml-full-verification/alerts-mobile.png"
) | Where-Object { Test-Path $_ }

$status = if ($scenarioSummaries | Where-Object { $_.status -eq "FAILED" }) { "FAILED" } else { "PASSED" }
$summary = [PSCustomObject]@{
    schemaVersion = "2026-08-19"
    status = $status
    runId = $context.RunId
    startedAt = ConvertTo-MlVerificationInstant $startedAt
    completedAt = ConvertTo-MlVerificationInstant (Get-Date)
    failedStep = ""
    failureMessage = ""
    serviceHealth = @{}
    resources = @{
        accountIds = @()
        deviceIds = @()
        datasetVersionIds = @()
        featureSnapshotIds = @()
        trainingRunIds = @()
        modelVersionIds = @()
        policyIds = @()
        predictionIds = @()
        alertIds = @()
        notificationAttemptIds = @()
    }
    scenarios = @($scenarioSummaries)
    modelEvidence = @()
    alertEvidence = @()
    evidence = @{
        summaryPaths = @(
            "artifacts/ml-production-smoke-summary.json",
            "artifacts/ml-anomaly-smoke-summary.json",
            "artifacts/ml-alert-incident-smoke-summary.json",
            $SummaryPath
        )
        reportPath = "artifacts/ml-verification-report-2026-08-19.md"
        screenshotPaths = @($screenshotPaths)
    }
    sanitization = @{
        secretsExcluded = $true
        sourceTokensExcluded = $true
        rawTelemetryExcluded = $true
    }
}

Write-MlVerificationSummary -Path $SummaryPath -Summary $summary
if ($env:GITHUB_STEP_SUMMARY) {
    @(
        "## ML full Docker verification",
        "",
        "- Run id: ``$($context.RunId)``",
        "- Status: ``$status``",
        "- Summary: ``$SummaryPath``"
    ) | Add-Content -Path $env:GITHUB_STEP_SUMMARY
}
$summary | ConvertTo-Json -Depth 80
if ($status -ne "PASSED") {
    exit 1
}
