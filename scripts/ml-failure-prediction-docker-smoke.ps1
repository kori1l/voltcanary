param(
    [string] $GatewayBaseUrl = "http://localhost:8080",
    [string] $IngestionBaseUrl = "http://localhost:8083",
    [string] $AnalyticsBaseUrl = "http://localhost:8085",
    [string] $MlBaseUrl = "http://localhost:8090",
    [int] $TimeoutSeconds = 420,
    [switch] $StartStack
)

$ErrorActionPreference = "Stop"

$RunId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$CorrelationId = "ml-failure-docker-smoke-$RunId"

function Write-Step {
    param([string] $Message)
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

function Submit-IngestionBackfill {
    param(
        [Parameter(Mandatory = $true)] [System.Collections.Generic.List[object]] $Readings,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [int] $ChunkSize = 12
    )

    $accepted = 0
    $duplicate = 0
    $rejected = 0
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
    }

    return [PSCustomObject]@{
        acceptedCount = $accepted
        duplicateCount = $duplicate
        rejectedCount = $rejected
    }
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
$outcomeAt = $start.AddDays(14)
$horizonDays = 7

Write-Step "Ingesting synthetic backfill telemetry"
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

$ingestion = Submit-IngestionBackfill -Readings $readings -Headers $sourceHeaders
Require-True ($ingestion.acceptedCount -gt 0) "Telemetry batch was not accepted"
Require-True ($ingestion.rejectedCount -eq 0) "Telemetry batch rejected $($ingestion.rejectedCount) readings"

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
}
Require-True ("$($dataset.status)" -eq "READY") "Dataset version was not READY: $($dataset.status)"
Require-True ($dataset.acceptedRows -ge 24) "Dataset did not materialize enough canonical rows"

$labels = Wait-For "positive horizon labels from maintenance outcome projection" {
    $candidate = Invoke-Api -Method POST -Path "/analytics/ml/datasets/horizon-labels" -Headers $authHeaders -Body @{
        datasetVersionId = "$($dataset.datasetVersionId)"
        horizonDays = $horizonDays
        generationVersion = "docker-smoke-failure-outcome-v1"
        includePreventiveMaintenance = $false
    }
    if ($candidate.positiveLabels -gt 0 -and $candidate.normalLabels -gt 0) { return $candidate }
    return $false
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
Require-True ($metricsText -match "analytics_ml_feature_generation_completed") "Prometheus output missing feature generation metric"
Require-True ($metricsText -match "analytics_ml_training_runs") "Prometheus output missing training runs metric"
Require-True ($metricsText -match "analytics_ml_inference_duration") "Prometheus output missing inference duration metric"
Require-True ($metricsText -match "analytics_ml_inference_fallback_rate") "Prometheus output missing inference fallback metric"

Write-Step "Docker smoke verification passed"
[PSCustomObject]@{
    runId = $RunId
    userId = "$($me.id)"
    accountId = "$($account.id)"
    deviceId = "$($device.id)"
    datasetVersionId = "$($dataset.datasetVersionId)"
    featureSnapshotId = "$($features.id)"
    horizonLabelLineageHash = "$($labels.lineageHash)"
    trainingRunId = "$($completedRun.id)"
    modelVersionId = "$($model.id)"
    artifactUri = "$($model.artifactUri)"
    artifactManifestUri = "$($model.artifactManifestUri)"
    predictionStatus = "$($prediction.predictionStatus)"
    failureRiskScore = $prediction.failureRiskScore
    inputHash = "$($prediction.inputHash)"
} | ConvertTo-Json -Depth 8
