param(
    [string] $GatewayBaseUrl = "http://localhost:8080",
    [string] $IngestionBaseUrl = "http://localhost:8083",
    [string] $AnalyticsBaseUrl = "http://localhost:8085",
    [string] $MlBaseUrl = "http://localhost:8090",
    [int] $TimeoutSeconds = 420,
    [switch] $StartStack,
    [switch] $VerifyLifecycleCleanup,
    [switch] $CiMode,
    [string] $SummaryPath = ""
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

$RunId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$CorrelationId = "ml-failure-docker-smoke-$RunId"
$script:CurrentSmokeStep = "initializing"

function Write-Step {
    param([string] $Message)
    $script:CurrentSmokeStep = $Message
    Write-Host "[$(Get-Date -Format HH:mm:ss)] $Message"
}

function ConvertTo-BodyJson {
    param([object] $Value)
    return ($Value | ConvertTo-Json -Depth 40 -Compress)
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Path,
        [object] $Body = $null,
        [hashtable] $Headers = @{},
        [string] $BaseUrl = $GatewayBaseUrl
    )

    $uri = "$BaseUrl$Path"
    try {
        if ($null -ne $Body) {
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $Headers -Body (ConvertTo-BodyJson $Body) -ContentType "application/json"
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $Headers
    } catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $detail = "$detail $($_.ErrorDetails.Message)"
        }
        throw "$Method $uri failed: $detail"
    }
}

function Wait-For {
    param(
        [Parameter(Mandatory = $true)] [string] $Description,
        [Parameter(Mandatory = $true)] [scriptblock] $Condition,
        [int] $Timeout = $TimeoutSeconds,
        [int] $DelaySeconds = 5
    )

    $deadline = (Get-Date).ToUniversalTime().AddSeconds($Timeout)
    $lastError = $null
    while ((Get-Date).ToUniversalTime() -lt $deadline) {
        try {
            $result = & $Condition
            if ($false -ne $result -and $null -ne $result) {
                return $result
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds $DelaySeconds
    }

    if ($lastError) {
        throw "Timed out waiting for $Description. Last error: $lastError"
    }
    throw "Timed out waiting for $Description."
}

function Require-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Require-Value {
    param([object] $Value, [string] $Message)
    if ($null -eq $Value -or "$Value" -eq "") {
        throw $Message
    }
}

function To-IsoInstant {
    param([datetime] $Value)
    return $Value.ToUniversalTime().ToString("o")
}

function Encode-QueryValue {
    param([string] $Value)
    return [System.Uri]::EscapeDataString($Value)
}

function Get-OptionalProperty {
    param([string] $VariableName, [string] $PropertyName)

    $variable = Get-Variable -Name $VariableName -Scope Script -ErrorAction SilentlyContinue
    if ($null -eq $variable -or $null -eq $variable.Value) {
        return ""
    }

    $value = $variable.Value.$PropertyName
    if ($null -eq $value) {
        return ""
    }
    return "$value"
}

function Write-SmokeFailureSummary {
    param([System.Management.Automation.ErrorRecord] $ErrorRecord)

    $summary = [PSCustomObject]@{
        status = "FAILED"
        runId = $RunId
        failedStep = $script:CurrentSmokeStep
        error = "$($ErrorRecord.Exception.Message)"
        accountId = (Get-OptionalProperty "account" "id")
        deviceId = (Get-OptionalProperty "device" "id")
        datasetVersionId = (Get-OptionalProperty "dataset" "datasetVersionId")
        featureSnapshotId = (Get-OptionalProperty "features" "id")
        trainingRunId = (Get-OptionalProperty "completedRun" "id")
        modelVersionId = (Get-OptionalProperty "model" "id")
        policySource = (Get-OptionalProperty "prediction" "policySource")
        predictionStatus = (Get-OptionalProperty "prediction" "predictionStatus")
        lifecycleCleanupVerified = [bool] $VerifyLifecycleCleanup
    }

    $summaryJson = $summary | ConvertTo-Json -Depth 8
    try {
        if ($SummaryPath) {
            $summaryDirectory = Split-Path -Parent $SummaryPath
            if ($summaryDirectory) {
                New-Item -ItemType Directory -Force -Path $summaryDirectory | Out-Null
            }
            $summaryJson | Set-Content -Path $SummaryPath -Encoding UTF8
        }
        if ($env:GITHUB_STEP_SUMMARY) {
            @(
                "## ML failure prediction smoke",
                "",
                "- Status: ``FAILED``",
                "- Run id: ``$RunId``",
                "- Failed step: ``$script:CurrentSmokeStep``",
                "- Error: ``$($ErrorRecord.Exception.Message)``"
            ) | Add-Content -Path $env:GITHUB_STEP_SUMMARY
        }
    } catch {
        Write-Warning "Could not write smoke failure summary: $($_.Exception.Message)"
    }
}

function Submit-IngestionBackfill {
    param(
        [Parameter(Mandatory = $true)] [System.Collections.Generic.List[object]] $Readings,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [int] $ChunkSize = 12
    )

    $accepted = 0
    $duplicate = 0
    $rejected = 0
    $rejectionMessages = New-Object System.Collections.Generic.List[string]
    for ($offset = 0; $offset -lt $Readings.Count; $offset += $ChunkSize) {
        $last = [Math]::Min($offset + $ChunkSize - 1, $Readings.Count - 1)
        $chunk = New-Object System.Collections.Generic.List[object]
        for ($index = $offset; $index -le $last; $index++) {
            $chunk.Add($Readings[$index])
        }

        $response = Invoke-Api -Method POST -Path "/ingestion/imports/readings/batch" -Headers $Headers -Body @{
            readings = $chunk
        }
        $accepted += [int] $response.acceptedCount
        $duplicate += [int] $response.duplicateCount
        $rejected += [int] $response.rejectedCount
        foreach ($item in @($response.items)) {
            if ("$($item.status)" -eq "rejected") {
                $message = "$($item.message)"
                if ($message -eq "") {
                    $message = "unspecified"
                }
                $rejectionMessages.Add($message) | Out-Null
            }
        }
    }

    return [PSCustomObject]@{
        acceptedCount = $accepted
        duplicateCount = $duplicate
        rejectedCount = $rejected
        rejectionReasons = @($rejectionMessages | Group-Object | Sort-Object Count -Descending | ForEach-Object {
            "$($_.Name)=$($_.Count)"
        })
    }
}

function Wait-For-IngestionSourceReadModel {
    param(
        [Parameter(Mandatory = $true)] [object] $Reading,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [Parameter(Mandatory = $true)] [string] $SourceId
    )

    Wait-For "ingestion source runtime config for $SourceId" {
        $response = Invoke-Api -Method POST -Path "/ingestion/imports/readings/batch" -Headers $Headers -Body @{
            readings = @($Reading)
        }
        if ([int] $response.rejectedCount -eq 0) {
            return $true
        }

        $reason = @($response.items | Where-Object { "$($_.status)" -eq "rejected" } | Select-Object -First 1).message
        if ("$reason" -eq "Unknown device data source" -or "$reason" -eq "Data source is not active" -or "$reason" -eq "Invalid source token") {
            return $false
        }
        throw "Probe telemetry was rejected: $reason"
    } | Out-Null
}

trap {
    Write-SmokeFailureSummary -ErrorRecord $_
    break
}

if ($StartStack) {
    Write-Step "Starting Docker app and observability profiles"
    $env:ANALYTICS_MAINTENANCE_RECONCILIATION_INTERVAL = "10s"
    $env:GATEWAY_RESPONSE_TIMEOUT = "30s"
    docker compose --profile app --profile observability up -d
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed"
    }
}

Write-Step "Waiting for gateway, analytics-service, and ml-service"
Wait-For "gateway health" {
    $health = Invoke-Api -Method GET -Path "/actuator/health"
    if ($health.status -eq "UP") { return $health }
    return $false
} | Out-Null
Wait-For "ingestion health" {
    $health = Invoke-Api -Method GET -Path "/actuator/health" -BaseUrl $IngestionBaseUrl
    if ($health.status -eq "UP") { return $health }
    return $false
} | Out-Null
Wait-For "analytics health" {
    $health = Invoke-Api -Method GET -Path "/actuator/health" -BaseUrl $AnalyticsBaseUrl
    if ($health.status -eq "UP") { return $health }
    return $false
} | Out-Null
Wait-For "ml-service health" {
    $health = Invoke-Api -Method GET -Path "/health" -BaseUrl $MlBaseUrl
    if ($health.status -eq "ok" -or $health.status -eq "UP") { return $health }
    return $false
} | Out-Null

$email = "ml-smoke-$RunId@example.test"
$password = "VoltCanary!2026-$RunId"

Write-Step "Registering verification user $email"
Invoke-Api -Method POST -Path "/auth/register" -Body @{
    email = $email
    password = $password
} | Out-Null
$login = Invoke-Api -Method POST -Path "/auth/login" -Body @{
    email = $email
    password = $password
}
Require-Value $login.accessToken "Login response did not include accessToken"
$authHeaders = @{
    Authorization = "Bearer $($login.accessToken)"
    "X-Correlation-Id" = $CorrelationId
}

$me = Invoke-Api -Method GET -Path "/auth/me" -Headers $authHeaders
$account = Invoke-Api -Method GET -Path "/accounts/current" -Headers $authHeaders
Require-Value $account.id "Current account was not created"

Write-Step "Creating device and source token"
$device = Invoke-Api -Method POST -Path "/devices" -Headers $authHeaders -Body @{
    locationId = $null
    name = "ML smoke washer $RunId"
    type = "GENERIC_APPLIANCE"
    manufacturer = "VoltCanary"
    model = "docker-smoke"
    metadata = @{
        verificationRunId = $RunId
        accountId = "$($account.id)"
        semanticDeviceType = "washer"
    }
}
Require-Value $device.id "Device creation did not return id"

$sourceId = "import-ml-smoke-$RunId"
$source = Invoke-Api -Method POST -Path "/devices/$($device.id)/data-sources" -Headers $authHeaders -Body @{
    sourceType = "API"
    protocol = "REST"
    externalId = $sourceId
}
$sourceToken = Invoke-Api -Method POST -Path "/devices/$($device.id)/data-sources/$($source.id)/token" -Headers $authHeaders
Require-Value $sourceToken.token "Source token response did not include token"
$sourceHeaders = @{
    Authorization = "Bearer $($sourceToken.token)"
    "X-Correlation-Id" = $CorrelationId
}

$end = (Get-Date).ToUniversalTime().AddHours(-2)
$start = $end.AddDays(-28)
$outcomeAt = $start.AddDays(21)
$horizonDays = 7

$readings = New-Object System.Collections.Generic.List[object]
$timestamp = $start
$index = 0
while ($timestamp -le $end) {
    $days = ($timestamp - $start).TotalDays
    $isPreFailure = $timestamp -gt $outcomeAt.AddDays(-$horizonDays) -and $timestamp -lt $outcomeAt
    $basePower = 820.0 + ([Math]::Sin($index / 3.0) * 18.0)
    $power = if ($isPreFailure) { 1030.0 + ($days * 9.0) + ([Math]::Sin($index) * 24.0) } else { $basePower }
    $readings.Add(@{
        deviceId = "$($device.id)"
        sourceId = $sourceId
        timestamp = To-IsoInstant $timestamp
        metrics = @{
            activePowerW = [Math]::Round($power, 3)
            voltageV = [Math]::Round(229.0 + ([Math]::Sin($index / 5.0) * 2.0), 3)
            currentA = [Math]::Round($power / 229.0, 3)
            energyWh = [Math]::Round(50000.0 + ($index * 20.0), 3)
        }
    })
    $timestamp = $timestamp.AddHours(4)
    $index++
}

Write-Step "Waiting for ingestion source runtime config"
Wait-For-IngestionSourceReadModel -Reading $readings[0] -Headers $sourceHeaders -SourceId $sourceId

Write-Step "Ingesting synthetic backfill telemetry"
$ingestion = Submit-IngestionBackfill -Readings $readings -Headers $sourceHeaders
Require-True ($ingestion.acceptedCount -gt 0) "Telemetry batch was not accepted"
$rejectionDetails = ""
if (@($ingestion.rejectionReasons).Count -gt 0) {
    $rejectionDetails = " Reasons: $(@($ingestion.rejectionReasons) -join '; ')"
}
Require-True ($ingestion.rejectedCount -eq 0) "Telemetry batch rejected $($ingestion.rejectedCount) readings.$rejectionDetails"

$fromQuery = Encode-QueryValue (To-IsoInstant $start)
$toQuery = Encode-QueryValue (To-IsoInstant $end)
$chartPath = "/telemetry/devices/$($device.id)/readings?metric=activePowerW&from=$fromQuery&to=$toQuery"
$chart = Wait-For "telemetry persistence" {
    $candidate = Invoke-Api -Method GET -Path $chartPath -Headers $authHeaders
    if (@($candidate.points).Count -ge 24) { return $candidate }
    return $false
}
Require-True (@($chart.points).Count -ge 24) "Telemetry chart did not return enough points"

Write-Step "Creating maintenance outcome and waiting for analytics projection"
$operation = Invoke-Api -Method POST -Path "/maintenance-operations" -Headers $authHeaders -Body @{
    title = "ML smoke corrective maintenance $RunId"
    description = "Synthetic outcome for failure prediction Docker verification."
    deviceIds = @("$($device.id)")
    startAt = To-IsoInstant ($outcomeAt.AddHours(-1))
    endAt = To-IsoInstant ($outcomeAt.AddHours(2))
    suppressAlerts = $false
    excludeFromAnalytics = $false
    excludeFromMl = $false
}
$outcome = Invoke-Api -Method POST -Path "/maintenance-operations/$($operation.id)/outcomes" -Headers $authHeaders -Body @{
    deviceId = "$($device.id)"
    maintenanceWindowId = $null
    observedAt = To-IsoInstant $outcomeAt
    resolvedAt = To-IsoInstant ($outcomeAt.AddHours(2))
    outcomeType = "CORRECTIVE_MAINTENANCE"
    failureMode = "bearing-drift"
    component = "motor"
    severity = "HIGH"
    downtimeMinutes = 90
    technicianNotes = "Synthetic failure outcome for ML verification."
    source = "OPERATOR"
    confidence = 0.95
}
Require-Value $outcome.id "Maintenance outcome was not created"

Write-Step "Previewing internal telemetry dataset workbench selection"
$previewBody = @{
    modelFamily = "FAILURE_PREDICTION"
    deviceIds = @("$($device.id)")
    deviceType = "washer"
    deviceMetadata = @{
        verificationRunId = $RunId
        manufacturer = "VoltCanary"
    }
    metricSelectionMode = "COMMON"
    selectedMetrics = @("activePowerW")
    from = To-IsoInstant $start
    to = To-IsoInstant $end
    horizonDays = $horizonDays
    splitStrategy = "TIME_BASED"
    splitConfigJson = '{"train":0.7,"validation":0.15,"test":0.15}'
    featureConfigVersion = "docker-smoke-feature-config-v1"
    minSamplesPerMetric = 24
    maxSamplingGapSeconds = 21600
    candidateLimit = 4
}
$preview = Wait-For "dataset workbench preview with failure readiness" {
    $candidate = Invoke-Api -Method POST -Path "/analytics/ml/datasets/internal-telemetry/preview" -Headers $authHeaders -Body $previewBody
    $activePowerMetric = @($candidate.availableMetrics | Where-Object { "$($_.metricName)" -eq "activePowerW" })
    if ($candidate.previewHash -and @($activePowerMetric).Count -eq 1 -and "$($candidate.status)" -ne "BLOCKED" -and $candidate.labelReadiness.positiveLabels -gt 0) {
        return $candidate
    }
    return $false
}
Require-Value $preview.previewId "Preview did not include previewId"
Require-True ("$($preview.previewHash)".StartsWith("sha256:")) "Preview hash is not sha256"
Require-True (@($preview.candidateWindows).Count -gt 0) "Preview did not include candidate windows"
Require-True ($preview.labelReadiness.positiveLabels -gt 0) "Preview did not include positive failure labels"

Write-Step "Creating READY internal telemetry dataset"
$dataset = Invoke-Api -Method POST -Path "/analytics/ml/datasets/internal-telemetry" -Headers $authHeaders -Body @{
    name = "Docker failure prediction dataset $RunId"
    description = "End-to-end Docker smoke dataset."
    deviceIds = @("$($device.id)")
    deviceType = "washer"
    deviceMetadata = @{
        verificationRunId = $RunId
        manufacturer = "VoltCanary"
    }
    metrics = @("activePowerW")
    from = To-IsoInstant $start
    to = To-IsoInstant $end
    splitStrategy = "TIME_BASED"
    splitConfigJson = '{"train":0.7,"validation":0.15,"test":0.15}'
    featureConfigVersion = "docker-smoke-feature-config-v1"
    minSamplesPerMetric = 24
    maxSamplingGapSeconds = 21600
    previewId = "$($preview.previewId)"
    previewHash = "$($preview.previewHash)"
    modelFamily = "FAILURE_PREDICTION"
    horizonDays = $horizonDays
    metricSelectionMode = "COMMON"
}
Require-True ("$($dataset.status)" -eq "READY") "Dataset version was not READY: $($dataset.status)"
Require-True ($dataset.acceptedRows -ge 24) "Dataset did not materialize enough canonical rows"

$labelJob = Invoke-Api -Method POST -Path "/analytics/ml/datasets/horizon-labels" -Headers $authHeaders -Body @{
    datasetVersionId = "$($dataset.datasetVersionId)"
    horizonDays = $horizonDays
    generationVersion = "docker-smoke-failure-outcome-v1"
    includePreventiveMaintenance = $false
}
Require-Value $labelJob.jobId "Horizon label generation did not return jobId"
$labels = Wait-For "positive horizon labels from maintenance outcome projection" {
    $candidate = Invoke-Api -Method GET -Path "/analytics/ml/datasets/horizon-labels/$($labelJob.jobId)" -Headers $authHeaders
    if ("$($candidate.status)" -eq "FAILED") {
        throw "Horizon label generation failed: $($candidate.failureReason)"
    }
    if ("$($candidate.status)" -ne "COMPLETED" -or $null -eq $candidate.result) {
        return $false
    }
    if ($candidate.result.positiveLabels -gt 0 -and $candidate.result.normalLabels -gt 0) {
        return $candidate.result
    }
    throw "Horizon label generation completed without positive and normal labels: positive=$($candidate.result.positiveLabels) normal=$($candidate.result.normalLabels) unresolved=$($candidate.result.unresolvedLabels) unobservable=$($candidate.result.unobservableRows)"
}
Require-True ($labels.positiveLabels -gt 0) "Horizon labels did not include positive labels"
Require-True ($labels.normalLabels -gt 0) "Horizon labels did not include normal labels"
Require-Value $labels.lineageHash "Horizon labels did not include lineageHash"

Write-Step "Generating FAILURE_PREDICTION feature snapshot"
$features = Invoke-Api -Method POST -Path "/analytics/ml/datasets/feature-snapshots" -Headers $authHeaders -Body @{
    datasetVersionId = "$($dataset.datasetVersionId)"
    featureSetVersion = "feature-set-v1"
    codeConfigVersion = "analytics-feature-pipeline-v1"
    rollingWindowSize = 6
    missingGapSeconds = 21600
    enabledFeatureFamilies = @("rolling_stats", "baseline_deviation", "missing_data", "time", "device_context", "label")
    modelFamily = "FAILURE_PREDICTION"
    horizonDays = $horizonDays
    featureTemplateId = "generic-appliance"
    featureTemplateVersion = "template-v1"
}
Require-True ($features.rowCount -gt 0) "Feature snapshot did not materialize rows"
Require-Value $features.featureSchemaHash "Feature snapshot did not include featureSchemaHash"
Require-Value $features.featureDataHash "Feature snapshot did not include featureDataHash"
Require-Value $features.artifactUri "Feature snapshot did not include artifactUri"

Write-Step "Queueing and waiting for failure prediction training"
$training = Invoke-Api -Method POST -Path "/analytics/ml/training-runs" -Headers $authHeaders -Body @{
    datasetVersionId = "$($dataset.datasetVersionId)"
    featureSnapshotId = "$($features.id)"
    modelFamily = "FAILURE_PREDICTION"
    algorithm = $null
    failureHorizonDays = $horizonDays
    minimumPositiveLabels = 1
    minimumLabelCoverage = 0.01
    approvalGateProfile = @{
        profileId = "failure-docker-smoke-ci"
        profileVersion = "2026-08-smoke"
        thresholdSource = "docker-smoke-synthetic-data"
        minimumPositiveLabels = 1
        minimumLabelCoverage = 0.01
        minimumPrecision = 0.6
        minimumRecall = 0.6
        minimumPrAuc = 0.6
        minimumPrAucLift = 0.0
        maximumFalseAlertsPerDeviceMonth = 10.0
        minimumLeadTimeDays = 0.0
        requireArtifactLoadTest = $true
        requireFeatureSchemaCompatibility = $true
    }
    hyperparametersJson = (@{ verificationRunId = $RunId } | ConvertTo-Json -Compress)
}
$completedRun = Wait-For "training run completion" {
    $runs = @(Invoke-Api -Method GET -Path "/analytics/ml/training-runs" -Headers $authHeaders)
    $candidate = $runs | Where-Object { "$($_.id)" -eq "$($training.id)" } | Select-Object -First 1
    if ($null -eq $candidate) { return $false }
    if ("$($candidate.status)" -in @("SUCCEEDED", "FAILED", "CANCELED")) { return $candidate }
    return $false
}
Require-True ("$($completedRun.status)" -eq "SUCCEEDED") "Training run did not succeed: status=$($completedRun.status) reason=$($completedRun.failureReason)"
Require-Value $completedRun.artifactUri "Training run did not record artifactUri"

$model = Wait-For "candidate model registration" {
    $models = @(Invoke-Api -Method GET -Path "/analytics/ml/models" -Headers $authHeaders)
    $candidate = $models |
        Where-Object { "$($_.featureSnapshotId)" -eq "$($features.id)" -and "$($_.family)" -eq "FAILURE_PREDICTION" } |
        Sort-Object createdAt -Descending |
        Select-Object -First 1
    if ($candidate) { return $candidate }
    return $false
}
Require-True ("$($model.status)" -eq "CANDIDATE") "Registered model was not CANDIDATE: $($model.status)"
Require-Value $model.artifactUri "Candidate model missing artifactUri"
Require-Value $model.artifactManifestUri "Candidate model missing artifactManifestUri"
Require-True ("$($model.artifactHash)".StartsWith("sha256:")) "Candidate model artifactHash is not sha256"
if ("$($completedRun.artifactHash)" -ne "") {
    Require-True ("$($model.artifactHash)" -eq "$($completedRun.artifactHash)") "Candidate model artifactHash did not match training artifactHash"
}
Require-True ("$($model.featureSchemaHash)" -eq "$($features.featureSchemaHash)") "Candidate model featureSchemaHash did not match feature snapshot"
Require-True ($model.artifactSizeBytes -gt 0) "Candidate model artifactSizeBytes is not positive"

Write-Step "Approving candidate model and deploying active device policy"
$approved = Invoke-Api -Method POST -Path "/analytics/ml/models/$($model.id)/approve" -Headers $authHeaders -Body @{
    reason = "Docker failure prediction smoke verification $RunId"
}
Require-True ("$($approved.status)" -eq "APPROVED") "Model approval failed: $($approved.status)"

$policy = Invoke-Api -Method POST -Path "/analytics/ml/deployment-policies" -Headers $authHeaders -Body @{
    scope = "DEVICE"
    deviceId = "$($device.id)"
    deviceType = $null
    modelVersionId = "$($model.id)"
    mode = "ACTIVE"
    canaryPercent = $null
    fallbackEnabled = $true
    reason = "Docker failure prediction smoke verification $RunId"
}
Require-True ("$($policy.mode)" -eq "ACTIVE") "Deployment policy was not ACTIVE"
Require-True ("$($policy.modelVersionId)" -eq "$($model.id)") "Deployment policy selected wrong model"

Invoke-Api -Method PUT -Path "/analytics/ml-settings/devices/$($device.id)" -Headers $authHeaders -Body @{
    mlEnabled = $true
    predictionMode = "MODEL_OR_BASELINE_HEURISTIC"
    confidenceWarningThreshold = 0.6
    maintenanceBehavior = "REDUCE_CONFIDENCE"
    explanationsVisible = $true
} | Out-Null

Write-Step "Running live recalculation and checking trained-model trace"
$recalculate = Invoke-Api -Method POST -Path "/analytics/devices/$($device.id)/recalculate" -Headers $authHeaders
Require-Value $recalculate.jobId "Recalculate response did not include jobId"
$job = Wait-For "analytics recalculation job" {
    $candidate = Invoke-Api -Method GET -Path "/analytics/devices/$($device.id)/jobs/$($recalculate.jobId)" -Headers $authHeaders
    if ("$($candidate.status)" -in @("COMPLETED", "FAILED", "SKIPPED")) { return $candidate }
    return $false
}
Require-True ("$($job.status)" -eq "COMPLETED") "Analytics job did not complete: status=$($job.status) error=$($job.errorMessage)"

$prediction = Invoke-Api -Method GET -Path "/analytics/devices/$($device.id)/predictions/latest" -Headers $authHeaders
Require-True ("$($prediction.predictionStatus)" -eq "TRAINED_MODEL") "Latest prediction was not TRAINED_MODEL: $($prediction.predictionStatus) fallback=$($prediction.fallbackReason)"
Require-True ("$($prediction.modelVersionId)" -eq "$($model.id)") "Latest prediction did not use deployed modelVersionId"
Require-True ("$($prediction.modelFamily)" -eq "FAILURE_PREDICTION") "Latest prediction modelFamily was not FAILURE_PREDICTION"
Require-True ("$($prediction.policySource)" -eq "DEVICE") "Latest prediction policySource was not DEVICE: $($prediction.policySource)"
Require-True ("$($prediction.fallbackReason)" -eq "") "Latest prediction unexpectedly used fallback: $($prediction.fallbackReason)"
Require-True ($prediction.failureRiskScore -ne $null) "Latest prediction missing failureRiskScore"
Require-True ($prediction.failureHorizonDays -eq $horizonDays) "Latest prediction horizon did not match"
Require-True (@($prediction.riskDrivers).Count -gt 0) "Latest prediction missing riskDrivers"
Require-Value $prediction.inputHash "Latest prediction missing inputHash"

Write-Step "Checking analytics monitoring signals"
try {
    $metricsText = [string] (Invoke-RestMethod -Method GET -Uri "$AnalyticsBaseUrl/actuator/prometheus")
} catch {
    $metricsText = & curl.exe -fsS "$AnalyticsBaseUrl/actuator/prometheus"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read analytics Prometheus metrics: $($_.Exception.Message)"
    }
}
$requiredMetricNames = @(
    "analytics_ml_feature_generation_completed",
    "analytics_ml_training_runs",
    "analytics_ml_inference_duration",
    "analytics_ml_inference_fallback_rate",
    "analytics_ml_inference_fallback_count",
    "analytics_ml_inference_feature_mismatch_count",
    "analytics_ml_inference_missing_feature_count",
    "analytics_ml_inference_shadow_count",
    "analytics_ml_inference_canary_included_count",
    "analytics_ml_inference_canary_excluded_count",
    "analytics_ml_quality_false_alerts_count",
    "analytics_ml_quality_false_alerts_per_device_month",
    "analytics_ml_quality_lead_time_days_avg"
)
foreach ($metricName in $requiredMetricNames) {
    Require-True ($metricsText -match $metricName) "Prometheus output missing $metricName metric"
}

if ($VerifyLifecycleCleanup) {
    Write-Step "Verifying ML lifecycle previews, blocks, disable/delete, and archive paths"

    $manualLabel = Invoke-Api -Method POST -Path "/analytics/ml/labels" -Headers $authHeaders -Body @{
        datasetVersionId = "$($dataset.datasetVersionId)"
        deviceId = "$($device.id)"
        externalDeviceKey = $null
        metricName = "activePowerW"
        labelStartAt = To-IsoInstant $start
        labelEndAt = To-IsoInstant ($start.AddHours(1))
        label = "ANOMALY"
        reason = "Docker lifecycle smoke manual review $RunId"
    }
    Require-Value $manualLabel.id "Manual label creation did not return id"

    $manualLabelImpact = Invoke-Api -Method GET -Path "/analytics/ml/labels/$($manualLabel.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($manualLabelImpact.blocked -eq $false) "Manual label delete impact was unexpectedly blocked"
    $deletedManualLabel = Invoke-Api -Method POST -Path "/analytics/ml/labels/$($manualLabel.id)/delete" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke manual label delete $RunId"
        force = $false
    }
    Require-Value $deletedManualLabel.id "Manual label delete did not return label"

    $generatedLabelList = @((Invoke-Api -Method GET -Path "/analytics/ml/labels?datasetVersionId=$($dataset.datasetVersionId)" -Headers $authHeaders) | ForEach-Object { $_ })
    $generatedLabel = $null
    foreach ($candidateLabel in $generatedLabelList) {
        if ("$($candidateLabel.lineageHash)" -eq "$($labels.lineageHash)") {
            $generatedLabel = $candidateLabel
            break
        }
    }
    if ($null -eq $generatedLabel) {
        foreach ($candidateLabel in $generatedLabelList) {
            if ("$($candidateLabel.source)" -ne "MANUAL" -and "$($candidateLabel.lineageHash)" -ne "") {
                $generatedLabel = $candidateLabel
                break
            }
        }
    }
    Require-Value $generatedLabel.id "Generated horizon label was not found for lifecycle check; labels returned=$(@($generatedLabelList).Count)"
    $generatedLabelImpact = Invoke-Api -Method GET -Path "/analytics/ml/labels/$($generatedLabel.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($generatedLabelImpact.blocked -eq $true) "Generated label delete impact was not blocked"

    $activePolicyImpact = Invoke-Api -Method GET -Path "/analytics/ml/deployment-policies/$($policy.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($activePolicyImpact.blocked -eq $true) "Active policy delete impact was not blocked"
    $disabledPolicy = Invoke-Api -Method POST -Path "/analytics/ml/deployment-policies/$($policy.id)/disable" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke policy disable $RunId"
        force = $false
    }
    Require-True ("$($disabledPolicy.mode)" -eq "DISABLED") "Policy disable did not set DISABLED mode"
    $disabledPolicyImpact = Invoke-Api -Method GET -Path "/analytics/ml/deployment-policies/$($policy.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($disabledPolicyImpact.blocked -eq $false) "Disabled policy delete impact was blocked"
    $deletedPolicy = Invoke-Api -Method POST -Path "/analytics/ml/deployment-policies/$($policy.id)/delete" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke policy delete $RunId"
        force = $false
    }
    Require-Value $deletedPolicy.id "Policy delete did not return policy"

    $modelDeleteImpact = Invoke-Api -Method GET -Path "/analytics/ml/models/$($model.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($modelDeleteImpact.blocked -eq $true) "Model delete impact was not blocked after prediction references"
    $currentModel = Invoke-Api -Method GET -Path "/analytics/ml/models/$($model.id)" -Headers $authHeaders
    if ("$($currentModel.status)" -ne "ACTIVE") {
        $archivedModel = Invoke-Api -Method POST -Path "/analytics/ml/models/$($model.id)/archive" -Headers $authHeaders -Body @{
            reason = "Docker lifecycle smoke model archive $RunId"
        }
        Require-True ("$($archivedModel.status)" -eq "ARCHIVED") "Model archive did not set ARCHIVED status"
    } else {
        Write-Step "Skipping model archive because deployed model is ACTIVE; delete impact block was verified"
    }

    $trainingDeleteImpact = Invoke-Api -Method GET -Path "/analytics/ml/training-runs/$($completedRun.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($trainingDeleteImpact.blocked -eq $true) "Training run delete impact was not blocked by model lineage"
    $archivedTraining = Invoke-Api -Method POST -Path "/analytics/ml/training-runs/$($completedRun.id)/archive" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke training archive $RunId"
        force = $false
    }
    Require-True ("$($archivedTraining.status)" -eq "ARCHIVED") "Training archive did not set ARCHIVED status"

    $snapshotDeleteImpact = Invoke-Api -Method GET -Path "/analytics/ml/datasets/feature-snapshots/$($features.id)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($snapshotDeleteImpact.blocked -eq $true) "Feature snapshot delete impact was not blocked by lineage"
    $archivedSnapshot = Invoke-Api -Method POST -Path "/analytics/ml/datasets/feature-snapshots/$($features.id)/archive" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke feature snapshot archive $RunId"
        force = $false
    }
    Require-True ("$($archivedSnapshot.id)" -eq "$($features.id)") "Feature snapshot archive returned the wrong snapshot id"

    $datasetDeleteImpact = Invoke-Api -Method GET -Path "/analytics/ml/datasets/versions/$($dataset.datasetVersionId)/lifecycle-impact?action=delete" -Headers $authHeaders
    Require-True ($datasetDeleteImpact.blocked -eq $true) "Dataset delete impact was not blocked by lineage"
    $archivedDataset = Invoke-Api -Method POST -Path "/analytics/ml/datasets/versions/$($dataset.datasetVersionId)/archive" -Headers $authHeaders -Body @{
        reason = "Docker lifecycle smoke dataset archive $RunId"
        force = $false
    }
    Require-True ("$($archivedDataset.status)" -eq "ARCHIVED") "Dataset archive did not set ARCHIVED status"
}

Write-Step "Docker smoke verification passed"
$summary = [PSCustomObject]@{
    status = "PASSED"
    runId = $RunId
    completedStep = $script:CurrentSmokeStep
    userId = "$($me.id)"
    accountId = "$($account.id)"
    deviceId = "$($device.id)"
    datasetVersionId = "$($dataset.datasetVersionId)"
    featureSnapshotId = "$($features.id)"
    horizonLabelLineageHash = "$($labels.lineageHash)"
    trainingRunId = "$($completedRun.id)"
    modelVersionId = "$($model.id)"
    artifactHash = "$($model.artifactHash)"
    featureSchemaHash = "$($features.featureSchemaHash)"
    artifactUri = "$($model.artifactUri)"
    artifactManifestUri = "$($model.artifactManifestUri)"
    policySource = "$($prediction.policySource)"
    fallbackReason = "$($prediction.fallbackReason)"
    predictionStatus = "$($prediction.predictionStatus)"
    failureRiskScore = $prediction.failureRiskScore
    failureHorizonDays = $prediction.failureHorizonDays
    inputHash = "$($prediction.inputHash)"
    lifecycleCleanupVerified = [bool] $VerifyLifecycleCleanup
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
if ($SummaryPath) {
    $summaryDirectory = Split-Path -Parent $SummaryPath
    if ($summaryDirectory) {
        New-Item -ItemType Directory -Force -Path $summaryDirectory | Out-Null
    }
    $summaryJson | Set-Content -Path $SummaryPath -Encoding UTF8
}
if ($env:GITHUB_STEP_SUMMARY) {
    @(
        "## ML failure prediction smoke",
        "",
        "- Run id: ``$RunId``",
        "- Model version id: ``$($model.id)``",
        "- Artifact hash: ``$($model.artifactHash)``",
        "- Feature schema hash: ``$($features.featureSchemaHash)``",
        "- Prediction status: ``$($prediction.predictionStatus)``",
        "- Policy source: ``$($prediction.policySource)``",
        "- Fallback reason: ``$($prediction.fallbackReason)``"
    ) | Add-Content -Path $env:GITHUB_STEP_SUMMARY
}
$summaryJson
