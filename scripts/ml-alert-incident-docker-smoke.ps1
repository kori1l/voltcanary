param(
    [string] $GatewayBaseUrl = "http://localhost:8080",
    [string] $IngestionBaseUrl = "http://localhost:8083",
    [string] $AnalyticsBaseUrl = "http://localhost:8085",
    [string] $AlertBaseUrl = "http://localhost:8086",
    [int] $TimeoutSeconds = 420,
    [switch] $StartStack,
    [switch] $CiMode,
    [string] $SummaryPath = ""
)

$ErrorActionPreference = "Stop"
if ($CiMode) { $ProgressPreference = "SilentlyContinue" }
. "$PSScriptRoot/ml-verification-fixtures.ps1"

$context = New-MlVerificationRunContext -Prefix "ml-alert"
$step = "initializing"
$auth = $null
$fixture = $null
$alert = $null
$reopenedAlert = $null
$attempts = @()

function Write-Step {
    param([string] $Message)
    $script:step = $Message
    Write-Host "[$(Get-Date -Format HH:mm:ss)] $Message"
}

function Finish-Summary {
    param([string] $Status, [string] $FailureMessage = "")

    $summary = [PSCustomObject]@{
        schemaVersion = "2026-08-19"
        status = $Status
        runId = $context.RunId
        startedAt = ConvertTo-MlVerificationInstant $context.StartedAt
        completedAt = ConvertTo-MlVerificationInstant (Get-Date)
        failedStep = $(if ($Status -eq "FAILED") { $script:step } else { "" })
        failureMessage = $FailureMessage
        scenarios = @(
            @{ id = "ML-E2E-006"; name = "ML-to-alert incident E2E"; status = $Status },
            @{ id = "ML-E2E-007"; name = "alert lifecycle E2E"; status = $Status },
            @{ id = "ML-E2E-008"; name = "notification attempt E2E"; status = $Status }
        )
        resources = @{
            accountIds = @(if ($auth -and $auth.Account) { "$($auth.Account.id)" })
            deviceIds = @(if ($fixture -and $fixture.Device) { "$($fixture.Device.id)" })
            datasetVersionIds = @()
            featureSnapshotIds = @()
            trainingRunIds = @()
            modelVersionIds = @()
            policyIds = @()
            predictionIds = @()
            alertIds = @(
                if ($alert) { "$($alert.id)" }
                if ($reopenedAlert) { "$($reopenedAlert.id)" }
            )
            notificationAttemptIds = @($attempts | ForEach-Object { "$($_.id)" })
        }
        serviceHealth = @{}
        alertEvidence = @(
            if ($alert) {
                @{
                    status = "$($alert.status)"
                    severity = "$($alert.severity)"
                    alertType = "$($alert.alertType)"
                    sourceEventId = "$($alert.sourceEventId)"
                    inputHash = "$($alert.calculationInputHash)"
                }
            }
        )
        evidence = @{ summaryPaths = @($SummaryPath); reportPath = ""; screenshotPaths = @() }
        sanitization = @{ secretsExcluded = $true; sourceTokensExcluded = $true; rawTelemetryExcluded = $true }
    }
    if ($SummaryPath) { Write-MlVerificationSummary -Path $SummaryPath -Summary $summary }
    return $summary
}

trap {
    $summary = Finish-Summary -Status "FAILED" -FailureMessage "$($_.Exception.Message)"
    $summary | ConvertTo-Json -Depth 80
    break
}

if ($StartStack) {
    Write-Step "Starting Docker app and observability profiles"
    docker compose --profile app --profile observability up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed" }
}

Write-Step "Checking gateway, ingestion, analytics, and alert health"
Wait-MlVerificationCondition "gateway health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $GatewayBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "ingestion health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $IngestionBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "analytics health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $AnalyticsBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "alert-service health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $AlertBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null

Write-Step "Creating tenant-scoped user, device, source token, and notification channel"
$auth = New-MlVerificationAuthenticatedUser -BaseUrl $GatewayBaseUrl -Context $context -EmailPrefix "ml-alert"
$auth.Headers["X-Account-Id"] = "$($auth.Account.id)"
$fixture = New-MlVerificationDeviceSource -BaseUrl $GatewayBaseUrl -Headers $auth.Headers -Context $context -Profile "spiking-washer"
$channel = Invoke-MlVerificationApi POST "/alerts/notification-channels" $GatewayBaseUrl -Headers $auth.Headers -Body @{
    name = "ML alert smoke channel $($context.RunId)"
    channelType = "WEBHOOK"
    config = @{ url = "http://localhost:9/ml-alert-smoke" }
    credentialsRef = ""
    enabled = $true
}

Write-Step "Creating device-scoped high-risk alert rule"
$rule = Invoke-MlVerificationApi POST "/alert-rules" $GatewayBaseUrl -Headers $auth.Headers -Body @{
    name = "ML alert smoke power spike $($context.RunId)"
    description = "Synthetic rule-compatible ML incident smoke."
    ruleType = "POWER_SPIKE"
    enabled = $true
    scopeType = "DEVICE"
    scopeId = "$($fixture.Device.id)"
    deviceType = $null
    metric = "activePowerW"
    conditionType = "ABOVE_THRESHOLD"
    threshold = 1200
    thresholdUnit = "W"
    baselineMultiplier = $null
    minPoints = 1
    severity = "HIGH"
    cooldownSeconds = 1
    deduplicationWindowSeconds = 3600
    notificationChannelIds = @("$($channel.id)")
    config = @{ verificationRunId = $context.RunId }
}
Assert-MlVerificationValue $rule.id "Alert rule creation did not return id"

Write-Step "Ingesting high-risk telemetry through the source token"
$end = (Get-Date).ToUniversalTime().AddMinutes(-2)
$start = $end.AddHours(-4)
$readings = New-MlWasherTelemetryProfile -Profile "spiking-washer" -DeviceId "$($fixture.Device.id)" -SourceId "$($fixture.SourceId)" -Start $start -End $end
$ingestion = Submit-MlVerificationTelemetryBatch -BaseUrl $GatewayBaseUrl -Readings $readings -Headers $fixture.SourceHeaders
Assert-MlVerification ($ingestion.acceptedCount -gt 0) "Telemetry batch was not accepted"
Assert-MlVerification ($ingestion.rejectedCount -eq 0) "Telemetry batch rejected $($ingestion.rejectedCount) readings"

Write-Step "Running analytics recalculation and waiting for alert-service incident"
$recalculate = Invoke-MlVerificationApi POST "/analytics/devices/$($fixture.Device.id)/recalculate" $GatewayBaseUrl -Headers $auth.Headers
Assert-MlVerificationValue $recalculate.jobId "Recalculate response did not include jobId"
Wait-MlVerificationCondition "analytics recalculation job" {
    $candidate = Invoke-MlVerificationApi GET "/analytics/devices/$($fixture.Device.id)/jobs/$($recalculate.jobId)" $GatewayBaseUrl -Headers $auth.Headers
    if ("$($candidate.status)" -in @("COMPLETED", "FAILED", "SKIPPED")) { $candidate } else { $false }
} -TimeoutSeconds $TimeoutSeconds | Out-Null

$alert = Wait-MlVerificationCondition "ML-created alert" {
    $alerts = @(Invoke-MlVerificationApi GET "/alerts?deviceId=$($fixture.Device.id)&status=OPEN&alertType=POWER_SPIKE&limit=10" $GatewayBaseUrl -Headers $auth.Headers)
    $candidate = $alerts | Select-Object -First 1
    if ($candidate) { $candidate } else { $false }
} -TimeoutSeconds $TimeoutSeconds
$alert = @($alert | Select-Object -First 1)[0]
Assert-MlVerification ("$($alert.status)" -eq "OPEN") "Created alert was not OPEN"
Assert-MlVerificationValue $alert.sourceEventId "Alert did not include sourceEventId"
Assert-MlVerificationValue $alert.calculationInputHash "Alert did not include calculationInputHash"

Write-Step "Checking notification attempt evidence"
$attempts = @(Wait-MlVerificationCondition "notification attempts" {
    $candidate = @(Invoke-MlVerificationApi GET "/alerts/$($alert.id)/notification-attempts" $GatewayBaseUrl -Headers $auth.Headers)
    if (@($candidate).Count -gt 0) { $candidate } else { $false }
} -TimeoutSeconds $TimeoutSeconds)

Write-Step "Verifying deduplication and lifecycle actions"
Invoke-MlVerificationApi POST "/analytics/devices/$($fixture.Device.id)/recalculate" $GatewayBaseUrl -Headers $auth.Headers | Out-Null
Start-Sleep -Seconds 8
$openBeforeAck = @(Invoke-MlVerificationApi GET "/alerts?deviceId=$($fixture.Device.id)&status=OPEN&alertType=POWER_SPIKE&limit=10" $GatewayBaseUrl -Headers $auth.Headers)
Assert-MlVerification (@($openBeforeAck).Count -eq 1) "Repeated condition created duplicate unresolved alerts"

$ack = Invoke-MlVerificationApi POST "/alerts/$($alert.id)/ack" $GatewayBaseUrl -Headers $auth.Headers
Assert-MlVerification ("$($ack.status)" -eq "ACKNOWLEDGED") "Alert acknowledge did not return ACKNOWLEDGED"
$resolve = Invoke-MlVerificationApi POST "/alerts/$($alert.id)/resolve" $GatewayBaseUrl -Headers $auth.Headers -Body @{
    reason = "Resolved by ML alert incident smoke $($context.RunId)"
}
Assert-MlVerification ("$($resolve.status)" -eq "RESOLVED") "Alert resolve did not return RESOLVED"

$reopenedRecalculate = Invoke-MlVerificationApi POST "/analytics/devices/$($fixture.Device.id)/recalculate" $GatewayBaseUrl -Headers $auth.Headers
Assert-MlVerificationValue $reopenedRecalculate.jobId "Reopen recalculation response did not include jobId"
Wait-MlVerificationCondition "reopen analytics recalculation job" {
    $candidate = Invoke-MlVerificationApi GET "/analytics/devices/$($fixture.Device.id)/jobs/$($reopenedRecalculate.jobId)" $GatewayBaseUrl -Headers $auth.Headers
    if ("$($candidate.status)" -in @("COMPLETED", "FAILED", "SKIPPED")) { $candidate } else { $false }
} -TimeoutSeconds $TimeoutSeconds | Out-Null
$reopenedAlert = Wait-MlVerificationCondition "reopened alert" {
    $alerts = @(Invoke-MlVerificationApi GET "/alerts?deviceId=$($fixture.Device.id)&status=OPEN&alertType=POWER_SPIKE&limit=10" $GatewayBaseUrl -Headers $auth.Headers)
    $candidate = $alerts | Where-Object { "$($_.id)" -ne "$($alert.id)" } | Select-Object -First 1
    if ($candidate) { $candidate } else { $false }
} -TimeoutSeconds $TimeoutSeconds
$reopenedAlert = @($reopenedAlert | Select-Object -First 1)[0]

Write-Step "Alert incident smoke passed"
$summary = Finish-Summary -Status "PASSED"
$summary | ConvertTo-Json -Depth 80
