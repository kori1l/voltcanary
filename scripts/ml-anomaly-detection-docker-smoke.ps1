param(
    [string] $GatewayBaseUrl = "http://localhost:8080",
    [string] $IngestionBaseUrl = "http://localhost:8083",
    [string] $AnalyticsBaseUrl = "http://localhost:8085",
    [string] $MlBaseUrl = "http://localhost:8090",
    [int] $TimeoutSeconds = 420,
    [switch] $StartStack,
    [switch] $CiMode,
    [string] $SummaryPath = ""
)

$ErrorActionPreference = "Stop"
if ($CiMode) { $ProgressPreference = "SilentlyContinue" }
. "$PSScriptRoot/ml-verification-fixtures.ps1"

$context = New-MlVerificationRunContext -Prefix "ml-anomaly"
$step = "initializing"

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
            @{ id = "ML-E2E-003"; name = "anomaly-detection docker smoke"; status = $Status },
            @{ id = "ML-E2E-006"; name = "ML-to-alert incident check"; status = "SKIPPED"; failureMessage = "Covered by ml-alert-incident-docker-smoke.ps1" }
        )
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
        serviceHealth = @{}
        modelEvidence = @()
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

Write-Step "Checking service health"
Wait-MlVerificationCondition "gateway health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $GatewayBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "ingestion health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $IngestionBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "analytics health" { $h = Invoke-MlVerificationApi GET "/actuator/health" $AnalyticsBaseUrl; if ($h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null
Wait-MlVerificationCondition "ml-service health" { $h = Invoke-MlVerificationApi GET "/health" $MlBaseUrl; if ($h.status -eq "ok" -or $h.status -eq "UP") { $h } else { $false } } -TimeoutSeconds $TimeoutSeconds | Out-Null

Write-Step "Verifying direct anomaly training and inference through ML-service"
$mlHeaders = @{
    "X-Internal-Service-Token" = "dev-internal-token"
    "X-Source-Service" = "analytics-service"
    "X-Correlation-Id" = $context.CorrelationId
}
$train = Invoke-MlVerificationApi POST "/train" $MlBaseUrl -Headers $mlHeaders -Body @{
    trainingRunId = "anomaly-$($context.RunId)"
    idempotencyKey = "ml-training:anomaly-$($context.RunId)"
    artifactBundleId = "model-anomaly-$($context.RunId)"
    datasetVersionId = "dataset-anomaly-$($context.RunId)"
    accountId = "account-anomaly-$($context.RunId)"
    sourceType = "INTERNAL_TELEMETRY"
    modelFamily = "ANOMALY_DETECTION"
    algorithm = "sklearn-isolation-forest-v1"
    featureSchemaHash = "sha256:anomaly-schema-$($context.RunId)"
    rows = @(
        @{ timestamp = "2026-08-01T00:00:00Z"; deviceId = "spiking-washer"; metric = "activePowerW"; value = 810; unit = "W"; label = "NORMAL" },
        @{ timestamp = "2026-08-01T00:01:00Z"; deviceId = "spiking-washer"; metric = "activePowerW"; value = 830; unit = "W"; label = "NORMAL" },
        @{ timestamp = "2026-08-01T00:02:00Z"; deviceId = "spiking-washer"; metric = "activePowerW"; value = 1760; unit = "W"; label = "ANOMALY" },
        @{ timestamp = "2026-08-01T00:03:00Z"; deviceId = "spiking-washer"; metric = "voltageV"; value = 231; unit = "V"; label = "NORMAL" },
        @{ timestamp = "2026-08-01T00:04:00Z"; deviceId = "spiking-washer"; metric = "currentA"; value = 7.7; unit = "A"; label = "ANOMALY" }
    )
}
Assert-MlVerificationValue $train.modelVersionId "Anomaly training did not return modelVersionId"
Assert-MlVerificationValue $train.artifactUri "Anomaly training did not return artifactUri"

$prediction = Invoke-MlVerificationApi POST "/inference" $MlBaseUrl -Headers $mlHeaders -Body @{
    deviceId = "spiking-washer"
    timestamp = "2026-08-01T16:00:00Z"
    metrics = @{ activePowerW = 1880; voltageV = 231; currentA = 8.1 }
    modelPreference = @{
        modelVersionId = "$($train.modelVersionId)"
        artifactUri = "$($train.artifactUri)"
        manifestUri = "$($train.manifestUri)"
        artifactHash = "$($train.artifactHash)"
        requiredFeatureSchemaHash = "$($train.featureSnapshot.featureSchemaHash)"
        modelFamily = "ANOMALY_DETECTION"
    }
}
Assert-MlVerification ("$($prediction.modelVersionId)" -eq "$($train.modelVersionId)") "Anomaly inference did not use trained modelVersionId"
Assert-MlVerification ("$($prediction.predictionStatus)" -in @("TRAINED_MODEL", "MODEL")) "Unexpected anomaly prediction status: $($prediction.predictionStatus)"

Write-Step "Anomaly detection smoke passed"
$summary = Finish-Summary -Status "PASSED"
$summary.modelEvidence = @(
    @{
        modelFamily = "ANOMALY_DETECTION"
        servingPath = "ML-service trained model inference"
        status = "$($prediction.predictionStatus)"
        modelVersionId = "$($train.modelVersionId)"
        artifactHash = "$($train.artifactHash)"
        featureSchemaHash = "sha256:anomaly-schema-$($context.RunId)"
        inputHash = "$($prediction.inputHash)"
        fallbackReason = ""
        policySource = ""
    }
)
if ($SummaryPath) { Write-MlVerificationSummary -Path $SummaryPath -Summary $summary }
$summary | ConvertTo-Json -Depth 80
