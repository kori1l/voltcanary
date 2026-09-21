param(
    [switch] $CiMode
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

function Assert-Equal {
    param(
        [object] $Actual,
        [object] $Expected,
        [string] $Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message Expected '$Expected' but got '$Actual'."
    }
}

function Assert-True {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$currentDaily = New-CapacityPreflight -ProfileName "current-5" -PhaseName "daily" -RunId "test-current-5-daily"
Assert-Equal $currentDaily.status "PASS" "current-5 should pass profile preflight."
Assert-Equal $currentDaily.workload.scheduledReadings 2880000 "daily scheduled reading arithmetic is wrong."
Assert-Equal $currentDaily.workload.expectedNumericValues 14400000 "current-5 value arithmetic is wrong."
Assert-True ($currentDaily.warnings -contains "current-5 evidence cannot satisfy the resume-10 capacity claim.") "current-5 warning is missing."

$resumeDaily = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "daily" -RunId "test-resume-10-daily"
Assert-Equal $resumeDaily.status "PASS" "resume-10 should pass after ten metrics are implemented."
Assert-Equal $resumeDaily.workload.scheduledReadings 2880000 "resume-10 scheduled reading arithmetic is wrong."
Assert-Equal $resumeDaily.workload.expectedNumericValues 28800000 "resume-10 value arithmetic is wrong."
Assert-Equal $resumeDaily.profileMetricNames.Count 10 "resume-10 should define ten metric names."
Assert-True ($resumeDaily.profileMetricNames -contains "temperatureC") "resume-10 should include temperatureC."

$hourly = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "hourly" -RunId "test-resume-10-hourly"
Assert-Equal $hourly.workload.measurementSeconds 3600 "hourly measured window must be one hour."
Assert-Equal $hourly.workload.scheduledReadings 120000 "hourly schedule must cover twelve readings for each of 10,000 devices."
Assert-Equal $hourly.workload.expectedNumericValues 1200000 "hourly resume profile must expect 1.2M numeric values."
Assert-Equal $hourly.verdicts.generation "NOT_RUN" "hourly arithmetic must not imply executed capacity evidence."
$hourlyFive = New-CapacityPreflight -ProfileName "current-5" -PhaseName "hourly"
Assert-Equal $hourlyFive.workload.expectedNumericValues 600000 "hourly five-value profile must remain distinct."

$baseline = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "baseline" -RunId "test-resume-10-baseline"
Assert-Equal $baseline.workload.scheduledReadings 60000 "baseline should schedule six 10,000-device cycles."
Assert-Equal $baseline.workload.expectedNumericValues 600000 "baseline expected values are wrong."
Assert-Equal $baseline.workload.phaseKind "periodic" "baseline should be a periodic phase."
Assert-Equal $baseline.workload.effectiveIntervalSeconds 300 "baseline effective interval should stay at 300 seconds."

$burst = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "burst" -RunId "test-resume-10-burst"
Assert-Equal $burst.workload.scheduledReadings 60000 "burst should preserve baseline reading totals."
Assert-Equal $burst.workload.expectedNumericValues 600000 "burst expected values are wrong."
Assert-Equal $burst.workload.phaseKind "burst" "burst phase kind is wrong."
Assert-Equal $burst.workload.burstWindowSeconds 30 "burst window should be explicit."

$stress = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "stress" -RunId "test-resume-10-stress"
Assert-Equal $stress.workload.scheduledReadings 90000 "stress should apply the configured 1.5x rate multiplier."
Assert-Equal $stress.workload.expectedNumericValues 900000 "stress expected values are wrong."
Assert-Equal $stress.workload.phaseKind "stress" "stress phase kind is wrong."
Assert-Equal $stress.workload.effectiveIntervalSeconds 200 "stress effective interval should compress the 300-second cadence."

$mixed = New-CapacityPreflight -ProfileName "resume-10" -PhaseName "mixed" -RunId "test-resume-10-mixed"
Assert-Equal $mixed.workload.scheduledReadings 60000 "mixed ingestion totals should match baseline ingestion totals."
Assert-Equal $mixed.workload.expectedNumericValues 600000 "mixed ingestion value totals are wrong."
Assert-Equal $mixed.workload.phaseKind "mixed" "mixed phase kind is wrong."
Assert-Equal $mixed.workload.readWorkload.latestRequests 18000 "mixed latest request arithmetic is wrong."
Assert-Equal $mixed.workload.readWorkload.chartRequests 3600 "mixed chart request arithmetic is wrong."
Assert-Equal $mixed.workload.readWorkload.exportRequests 30 "mixed export request arithmetic is wrong."
Assert-Equal $mixed.workload.readWorkload.totalReadRequests 21630 "mixed total read request arithmetic is wrong."

$smoke = New-CapacityPreflight -ProfileName "current-5" -PhaseName "smoke" -RunId "test-current-5-smoke"
Assert-Equal $smoke.workload.deviceCount 100 "smoke device override is wrong."
Assert-Equal $smoke.workload.scheduledReadings 1000 "smoke scheduled reading arithmetic is wrong."
Assert-Equal $smoke.workload.expectedNumericValues 5000 "smoke value arithmetic is wrong."

$unsupportedProfilePath = Join-Path ([System.IO.Path]::GetTempPath()) "voltcanary-unsupported-profile-$([Guid]::NewGuid().ToString("N")).json"
$profileDocument = Get-CapacityProfileDocument
$profileDocument.profiles | Add-Member -NotePropertyName "unsupported-10" -NotePropertyValue ([PSCustomObject]@{
    description = "Unsupported test profile with one missing metric."
    deviceCount = 10000
    intervalSeconds = 300
    requiredNumericMetricCount = 10
    metricNames = @(
        "activePowerW",
        "voltageV",
        "currentA",
        "energyWh",
        "rssi",
        "temperatureC",
        "vibrationMmS",
        "runtimeHours",
        "powerFactor",
        "notSupportedMetric"
    )
    batchSize = 100
    protocol = "rest"
    seed = 424242
})
$profileDocument | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $unsupportedProfilePath -Encoding UTF8
$unsupportedDaily = New-CapacityPreflight -ProfileName "unsupported-10" -PhaseName "daily" -RunId "test-unsupported-10-daily" -ProfilePath $unsupportedProfilePath
Assert-Equal $unsupportedDaily.status "BLOCKED" "unsupported profile should remain blocked."
Assert-True ($unsupportedDaily.blockers.Count -gt 0) "unsupported profile blocker list is missing."

$workerRanges = Get-CapacityWorkerRanges -ScheduledReadings 10 -WorkerCount 3
Assert-Equal $workerRanges.Count 3 "worker range count is wrong."
Assert-Equal $workerRanges[0].startSequenceInclusive 0 "first worker range starts at the wrong sequence."
Assert-Equal $workerRanges[0].endSequenceExclusive 4 "first worker range should receive the first remainder item."
Assert-Equal $workerRanges[1].startSequenceInclusive 4 "second worker range starts at the wrong sequence."
Assert-Equal $workerRanges[2].endSequenceExclusive 10 "last worker range should end at total scheduled readings."
Assert-Equal (($workerRanges | Measure-Object -Property scheduledReadings -Sum).Sum) 10 "worker ranges do not cover all readings."

$preparedSmoke = New-CapacityFixtureSchedule `
    -ProfileName "current-5" `
    -PhaseName "smoke" `
    -RunId "test-current-5-smoke-schedule" `
    -TenantCount 10 `
    -WorkerCount 3 `
    -MaxMaterializedReadings 2000
Assert-Equal $preparedSmoke.status "PASS" "current-5 smoke schedule should pass."
Assert-Equal $preparedSmoke.fixture.tenantCount 10 "tenant count is wrong."
Assert-Equal $preparedSmoke.fixture.deviceCount 100 "fixture device count is wrong."
Assert-Equal $preparedSmoke.schedule.materializedReadingCount 1000 "smoke schedule should materialize all readings."
Assert-Equal $preparedSmoke.schedule.scheduleComplete $true "smoke schedule should be complete."
Assert-Equal $preparedSmoke.schedule.materializedReadings[0].sequence 0 "first sequence is wrong."
Assert-Equal $preparedSmoke.schedule.materializedReadings[0].recordedAtOffsetSeconds 0 "first offset is wrong."
Assert-Equal $preparedSmoke.schedule.materializedReadings[99].phaseOffsetSeconds 29 "last first-cycle smoke phase offset is wrong."

$preparedDaily = New-CapacityFixtureSchedule `
    -ProfileName "current-5" `
    -PhaseName "daily" `
    -RunId "test-current-5-daily-schedule" `
    -TenantCount 100 `
    -WorkerCount 4 `
    -MaxMaterializedReadings 25
Assert-Equal $preparedDaily.status "PASS" "current-5 daily schedule should pass."
Assert-Equal $preparedDaily.schedule.materializedReadingCount 25 "daily schedule materialized limit is wrong."
Assert-Equal $preparedDaily.schedule.scheduleComplete $false "daily schedule should be partial when limited."
Assert-Equal (($preparedDaily.schedule.workerRanges | Measure-Object -Property scheduledReadings -Sum).Sum) 2880000 "daily worker ranges do not cover all readings."

$preparedStress = New-CapacityFixtureSchedule `
    -ProfileName "resume-10" `
    -PhaseName "stress" `
    -RunId "test-resume-10-stress-schedule" `
    -TenantCount 100 `
    -WorkerCount 3 `
    -MaxMaterializedReadings 20000
Assert-Equal $preparedStress.schedule.scheduleComplete $false "stress schedule should remain partial when limited."
Assert-Equal $preparedStress.schedule.materializedReadingCount 20000 "stress schedule materialized count is wrong."
Assert-Equal (($preparedStress.schedule.workerRanges | Measure-Object -Property scheduledReadings -Sum).Sum) 90000 "stress worker ranges do not cover all configured readings."
Assert-True (($preparedStress.schedule.materializedReadings | Where-Object { [int64] $_.recordedAtOffsetSeconds -ge 1800 } | Measure-Object).Count -eq 0) "stress timestamps must stay inside the measured window."
Assert-Equal $preparedStress.schedule.materializedReadings[19999].recordedAtOffsetSeconds 399 "stress second-cycle phase should use the compressed effective interval."

$blockedSchedule = New-CapacityFixtureSchedule `
    -ProfileName "unsupported-10" `
    -PhaseName "daily" `
    -RunId "test-unsupported-10-daily-schedule" `
    -ProfilePath $unsupportedProfilePath
Assert-Equal $blockedSchedule.status "BLOCKED" "unsupported profile schedule should be blocked."
Assert-True ($null -eq $blockedSchedule.fixture) "blocked schedule should not create fixture data."

$environment = New-CapacityEnvironmentManifest -RunId "test-environment-manifest"
Assert-Equal $environment.runId "test-environment-manifest" "environment manifest run id is wrong."
Assert-True ($environment.metricCatalog.profileCatalogNames -contains "activePowerW") "environment manifest metric catalog is missing activePowerW."
Assert-Equal $environment.rateLimits.ingestion.sourceRequestsPerWindow "600" "ingestion source rate-limit default is wrong."
Assert-Equal $environment.rateLimits.gateway.ingestionRequestsPerWindow "120" "gateway ingestion rate-limit default is wrong."
Assert-Equal $environment.kafka.defaultPartitions "3" "Kafka default partition capture is wrong."
$secretDefaults = @($environment.configDefaults | Where-Object { $_.key -match "PASSWORD|SECRET|TOKEN|PRIVATE|PUBLIC_KEYS|API_KEY|ACCESS_KEY" })
Assert-True (($secretDefaults | Where-Object { $_.defaultValue -ne "REDACTED" } | Measure-Object).Count -eq 0) "secret-like defaults must be redacted."

$observability = New-CapacityObservabilityInventory -RunId "test-observability"
Assert-Equal $observability.status "PASS" "observability inventory should find required meters, histograms and scrape jobs."
Assert-True (($observability.expectedMeters | Where-Object { $_.name -eq "voltcanary.ingestion.processing" -and $_.status -eq "PRESENT" } | Measure-Object).Count -eq 1) "ingestion processing meter should be inventoried."
Assert-True (($observability.histogramBuckets | Where-Object { $_.service -eq "telemetry-service" -and $_.status -eq "CONFIGURED" } | Measure-Object).Count -eq 1) "telemetry histogram buckets should be configured."
Assert-True (($observability.scrapeJobs | Where-Object { $_.job -eq "cadvisor" -and $_.status -eq "CONFIGURED" } | Measure-Object).Count -eq 1) "cAdvisor scrape job should be configured."
Assert-True (($observability.queries | Where-Object { $_.name -eq "redpanda_consumer_lag" } | Measure-Object).Count -eq 1) "consumer lag query should be documented."

$storagePlan = New-CapacityStorageFixturePlan -ProfileName "current-5" -Mode "storage-only-sql" -RunId "test-storage-current-5"
Assert-Equal $storagePlan.status "PLANNED" "current-5 storage fixture should be planned."
Assert-Equal $storagePlan.targetDistribution.days 30 "storage fixture should cover 30 days by default."
Assert-Equal $storagePlan.targetDistribution.totalNumericValues 432000000 "current-5 storage fixture value count is wrong."
Assert-Equal $storagePlan.targetDistribution.windowCount 120 "default storage checkpoint window count is wrong."
Assert-Equal $storagePlan.mode "storage-only-sql" "storage fixture mode should be explicit."
Assert-True ($storagePlan.explicitScope -match "does not prove ingestion") "storage-only scope should not imply ingestion evidence."
Assert-Equal $storagePlan.diskEstimate.status "ESTIMATE_UNTIL_PILOT_MEASURED" "unmeasured pilot should be labeled as estimate."
$storageCheckpoint = Test-CapacityStorageFixtureCheckpoint -Plan $storagePlan
Assert-Equal $storageCheckpoint.status "PASS" "storage fixture checkpoints should validate."
$resumeStorage = New-CapacityStorageFixturePlan -ProfileName "resume-10" -Mode "storage-only-sql" -RunId "test-storage-resume-10"
Assert-Equal $resumeStorage.status "PLANNED" "resume-10 storage fixture should be planned with the ten-metric contract."
Assert-Equal $resumeStorage.targetDistribution.totalNumericValues 864000000 "resume-10 storage fixture value count is wrong."

$threw = $false
try {
    New-CapacityPreflight -ProfileName "missing" -PhaseName "daily" -RunId "test-missing" | Out-Null
} catch {
    $threw = $true
}
Assert-True $threw "Invalid profile should throw."

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "voltcanary-load-tests-$([Guid]::NewGuid().ToString("N"))"
try {
    $written = Write-CapacityEvidence -Preflight $currentDaily -OutputRoot $tempRoot
    Assert-True (Test-Path -LiteralPath $written.summaryPath) "summary.json was not written."
    Assert-True (Test-Path -LiteralPath $written.manifestPath) "manifest.json was not written."
    Assert-True (Test-Path -LiteralPath $written.reportPath) "report.md was not written."
    $initialSummary = Get-Content -Raw -LiteralPath $written.summaryPath | ConvertFrom-Json
    Assert-Equal $initialSummary.overallEvidenceStatus "NOT_RUN" "initial evidence status should retain missing generator/reconciliation claims."
    Assert-Equal $initialSummary.generatorValidity.status "NOT_RUN" "initial generator validity should be NOT_RUN."

    $scheduled = Write-CapacityFixtureSchedule -Prepared $preparedSmoke -OutputRoot $tempRoot
    Assert-True (Test-Path -LiteralPath $scheduled.fixturePath) "fixture-manifest.json was not written."
    Assert-True (Test-Path -LiteralPath $scheduled.schedulePath) "schedule-manifest.json was not written."

    $writtenStorage = Write-CapacityStorageFixturePlan -Plan $storagePlan -RunDirectory $scheduled.runDirectory
    Assert-True (Test-Path -LiteralPath $writtenStorage.storageFixturePath) "storage-fixture.json was not written."
    $storageSummary = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "summary.json") | ConvertFrom-Json
    Assert-Equal $storageSummary.storageFixtureStatus "PLANNED" "summary should retain storage fixture status."
    Assert-Equal $storageSummary.verdicts.monthlyVolume "INCONCLUSIVE" "planned storage fixture should not pass monthly volume claim."

    $monthlyTrial = New-CapacityMonthlyTrialPlan -RunDirectory $scheduled.runDirectory -ColdQueryRepeats 2 -WarmQueryRepeats 4 -LiveWriteSeconds 120
    Assert-Equal $monthlyTrial.status "INCONCLUSIVE" "monthly trial should remain inconclusive until full volume is loaded."
    Assert-Equal $monthlyTrial.queryPhases[0].name "cold" "monthly trial should include a cold query phase."
    Assert-Equal $monthlyTrial.queryPhases[1].name "warm" "monthly trial should include a warm query phase."
    Assert-Equal $monthlyTrial.liveWriteTrial.durationSeconds 120 "monthly trial should retain live write duration."
    Assert-True (($monthlyTrial.requiredInventory | Where-Object { $_.name -eq "indexes" } | Measure-Object).Count -eq 1) "monthly trial should require index inventory."
    $writtenMonthly = Write-CapacityMonthlyTrialPlan -Plan $monthlyTrial -RunDirectory $scheduled.runDirectory
    Assert-True (Test-Path -LiteralPath $writtenMonthly.monthlyTrialPath) "monthly-trial.json was not written."
    $monthlySummary = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "summary.json") | ConvertFrom-Json
    Assert-Equal $monthlySummary.monthlyTrialStatus "INCONCLUSIVE" "summary should retain monthly trial status."
    Assert-Equal $monthlySummary.verdicts.monthlyVolume "INCONCLUSIVE" "monthly trial planning should not pass monthly volume claim."

    $scalingTrial = New-CapacityScalingTrialPlan -RunDirectory $scheduled.runDirectory -ReplicaCounts @(1, 2, 3) -HpaSampleSeconds 15
    Assert-Equal $scalingTrial.status "PLANNED" "scaling trial should be planned."
    Assert-Equal $scalingTrial.fixedReplicaRuns.Count 3 "scaling trial should include fixed replica runs."
    Assert-Equal $scalingTrial.hpaTimeline.sampleSeconds 15 "scaling trial should retain HPA sample cadence."
    Assert-True ($scalingTrial.comparisonPolicy -match "do not infer") "scaling policy should avoid replica-count-only claims."
    $writtenScaling = Write-CapacityScalingTrialPlan -Plan $scalingTrial -RunDirectory $scheduled.runDirectory
    Assert-True (Test-Path -LiteralPath $writtenScaling.scalingTrialPath) "scaling-trial.json was not written."

    $recoveryTrial = New-CapacityRecoveryTrialPlan -RunDirectory $scheduled.runDirectory -FaultTargets @("redis", "database") -OutageSeconds 10 -DrainSeconds 20
    Assert-Equal $recoveryTrial.status "PLANNED" "recovery trial should be planned."
    Assert-Equal $recoveryTrial.trials.Count 2 "recovery trial should include requested fault targets."
    Assert-True ($recoveryTrial.scope -match "do not prove replicated HA") "recovery scope should distinguish restart from HA."
    $writtenRecovery = Write-CapacityRecoveryTrialPlan -Plan $recoveryTrial -RunDirectory $scheduled.runDirectory
    Assert-True (Test-Path -LiteralPath $writtenRecovery.recoveryTrialPath) "recovery-trial.json was not written."
    $trialSummary = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "summary.json") | ConvertFrom-Json
    Assert-Equal $trialSummary.verdicts.scaling "INCONCLUSIVE" "scaling plan should not pass scaling claim."
    Assert-Equal $trialSummary.verdicts.recovery "INCONCLUSIVE" "recovery plan should not pass recovery claim."
    $trialReport = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "report.md")
    Assert-True ($trialReport -match "Monthly Query/Write Trial") "report should include monthly trial section."
    Assert-True ($trialReport -match "Scaling Trial") "report should include scaling trial section."
    Assert-True ($trialReport -match "Recovery Trial") "report should include recovery trial section."

    $blockedTempRoot = Join-Path $tempRoot "blocked-monthly"
    New-Item -ItemType Directory -Path $blockedTempRoot -Force | Out-Null
    $blockedMonthly = New-CapacityMonthlyTrialPlan -RunDirectory $blockedTempRoot
    Assert-Equal $blockedMonthly.status "BLOCKED" "monthly trial should block without a storage fixture."

    $restPlan = New-CapacityGenerationPlan `
        -RunDirectory $scheduled.runDirectory `
        -Protocol "rest" `
        -WorkerIndex 0 `
        -MaxReadings 12
    Assert-Equal $restPlan.status "PLANNED" "REST generation plan should be planned."
    Assert-Equal $restPlan.tool "k6" "REST generation should use k6."
    Assert-Equal $restPlan.pinnedToolVersion "0.52.0" "REST generation should pin the k6 version."
    Assert-Equal $restPlan.toolImage "grafana/k6:0.52.0" "REST generation should pin the k6 image."
    Assert-Equal $restPlan.accounting.workerMaterializedReadings 12 "REST plan should cap materialized readings."
    Assert-Equal $restPlan.accounting.batchSize 1 "REST plan should use source-scoped one-reading requests."
    Assert-Equal $restPlan.accounting.profileBatchSize 100 "REST plan should retain profile batch size as metadata."
    Assert-Equal $restPlan.accounting.expectedRequests 12 "REST plan should account for one request per source-scoped reading."
    Assert-Equal $restPlan.arrivalSchedule.executor "constant-arrival-rate" "REST plan should use k6 constant arrival rate."
    Assert-Equal $restPlan.arrivalSchedule.rate 12 "REST arrival rate should preserve exact request total."
    Assert-Equal $restPlan.arrivalSchedule.timeUnit "300s" "REST arrival time unit should preserve measurement duration."
    Assert-Equal $restPlan.workerPartition.workerCount 3 "REST plan should retain total worker count."
    Assert-Equal $restPlan.workerPartition.workerIndex 0 "REST plan should retain worker index."
    Assert-Equal $restPlan.workerPartition.startSequenceInclusive 0 "REST plan should retain worker start sequence."
    Assert-Equal $restPlan.workerPartition.materializedEndSequenceExclusive 12 "REST plan should retain materialized range."
    Assert-True ($restPlan.command -match " run --compatibility-mode=base ") "REST plan command should describe k6 execution."
    Assert-True ($restPlan.command -match "grafana/k6:0.52.0") "REST plan command should use the pinned k6 image."
    $writtenPlan = Write-CapacityGenerationPlan -Plan $restPlan -RunDirectory $scheduled.runDirectory
    Assert-True (Test-Path -LiteralPath $writtenPlan.generationManifestPath) "generation-manifest.json was not written."

    $mqttPlan = New-CapacityGenerationPlan `
        -RunDirectory $scheduled.runDirectory `
        -Protocol "mqtt" `
        -WorkerIndex 0 `
        -MaxReadings 5
    Assert-Equal $mqttPlan.tool "mqtt" "MQTT generation plan should identify the MQTT generator."
    Assert-Equal $mqttPlan.pinnedToolVersion "PowerShell raw MQTT 3.1.1 TCP publisher" "MQTT generation should pin the client implementation."
    Assert-Equal $mqttPlan.toolImage "native-powershell" "MQTT generation should not advertise the mosquitto image."
    Assert-Equal $mqttPlan.mqtt.qos 1 "MQTT plan should record explicit QoS."
    Assert-Equal $mqttPlan.mqtt.requestedConnections 1 "MQTT plan should record requested connections."
    Assert-True ($mqttPlan.command -match "mqtt-generator.ps1") "MQTT plan command should use the repository generator."
    Assert-True ($mqttPlan.acceptancePolicy -match "transport-only") "MQTT plan should not equate broker ack with acceptance."
    Assert-True ($mqttPlan.mqtt.persistenceEvidence -match "reconciliation") "MQTT plan should require persistence reconciliation."

    $generation = New-CapacityGenerationResult `
        -Plan $restPlan `
        -AcceptedSequences @(0, 1) `
        -DuplicateSequences @(1) `
        -UnknownSequences @(2) `
        -TimeoutAfterAcceptSequences @(3) `
        -DroppedScheduledReadings 1
    Assert-Equal $generation.status "INCONCLUSIVE" "unknown or dropped generation outcomes should be inconclusive."
    Assert-Equal $generation.acceptedUniqueReadings 2 "accepted unique reading accounting is wrong."
    Assert-Equal $generation.retries 2 "retry accounting should include unknown and timeout-after-accept identities."
    Assert-Equal $generation.expectedCommittedNumericValues 10 "expected committed value accounting is wrong."
    $writtenGeneration = Write-CapacityGenerationResult -RunDirectory $scheduled.runDirectory -Result $generation
    Assert-True (Test-Path -LiteralPath $writtenGeneration.generationResultPath) "generation-result.json was not written."
    $generationSummary = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "summary.json") | ConvertFrom-Json
    Assert-Equal $generationSummary.generatorValidity.status "INCONCLUSIVE" "dropped or ambiguous generator evidence should be inconclusive."
    $generationReport = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "report.md")
    Assert-True ($generationReport -match "Generator Validity") "readable report should include generator validity."
    Assert-True ($generationReport -match "ambiguous-outcomes") "readable report should retain ambiguous generator outcomes."

    $k6OutcomePath = Join-Path $scheduled.runDirectory "k6-outcomes.ndjson"
    @(
        [PSCustomObject]@{ type = "capacity-rest-batch"; runId = $restPlan.runId; workerIndex = 0; iteration = 0; attempt = 0; status = "accepted"; sequences = @(0, 1, 2); itemStatuses = @("accepted", "duplicate", "rejected") },
        [PSCustomObject]@{ type = "capacity-rest-batch"; runId = $restPlan.runId; workerIndex = 0; iteration = 1; attempt = 0; status = "unknown"; sequences = @(3); itemStatuses = @() },
        [PSCustomObject]@{ type = "capacity-rest-batch"; runId = $restPlan.runId; workerIndex = 0; iteration = 2; attempt = 0; status = "unknown"; sequences = @(4); itemStatuses = @() },
        [PSCustomObject]@{ type = "capacity-rest-batch"; runId = $restPlan.runId; workerIndex = 0; iteration = 2; attempt = 1; status = "accepted"; sequences = @(4); itemStatuses = @("accepted") }
    ) | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 10 } | Set-Content -LiteralPath $k6OutcomePath -Encoding UTF8
    $importedK6 = ConvertFrom-CapacityK6RestOutcomes -Plan $restPlan -OutcomePath $k6OutcomePath
    Assert-Equal $importedK6.status "FAIL" "rejected k6 item outcome should fail generation result."
    Assert-Equal $importedK6.startedReadings 5 "k6 import should count unique started readings."
    Assert-Equal $importedK6.transportAttempts 6 "k6 import should count every attempted item."
    Assert-Equal $importedK6.acceptedUniqueReadings 2 "k6 import should keep final accepted outcomes."
    Assert-Equal $importedK6.duplicates 1 "k6 import should retain duplicate item outcomes."
    Assert-Equal $importedK6.rejectedReadings 1 "k6 import should retain rejected item outcomes."
    Assert-Equal $importedK6.unknownOutcomes 1 "k6 import should retain unresolved final unknown outcomes."
    Assert-Equal $importedK6.retries 2 "k6 import should count retried and unresolved identities."
    Assert-Equal $importedK6.droppedScheduledReadings 7 "k6 import should count materialized readings absent from outcome logs."

    $partialBatch = New-CapacityGenerationResult `
        -Plan $restPlan `
        -AcceptedSequences @(0, 1) `
        -RejectedSequences @(2)
    Assert-Equal $partialBatch.status "FAIL" "batch partial success with rejected items should fail generation accounting."
    Assert-Equal $partialBatch.rejectedReadings 1 "batch partial success should retain rejected item accounting."

    $badGeneration = New-CapacityGenerationResult `
        -Plan $restPlan `
        -AcceptedSequences @(999)
    Assert-Equal $badGeneration.status "FAIL" "unexpected outcome sequence should fail generation accounting."
    Assert-Equal $badGeneration.unexpectedOutcomeSequences[0] 999 "unexpected sequence should be retained."

    $mqttTransport = New-CapacityGenerationResult `
        -Plan $mqttPlan `
        -UnknownSequences @(0) `
        -TransportErrorSequences @(0) `
        -ReconnectAttempts 1 `
        -ConnectionCount 2 `
        -Qos 1 `
        -ClientVersion "PowerShell raw MQTT 3.1.1 TCP publisher"
    Assert-Equal $mqttTransport.status "INCONCLUSIVE" "MQTT transport errors should require reconciliation and remain inconclusive."
    Assert-Equal $mqttTransport.transportErrors 1 "MQTT transport errors should be counted."
    Assert-Equal $mqttTransport.reconnectAttempts 1 "MQTT reconnect attempts should be counted."
    Assert-Equal $mqttTransport.connectionCount 2 "MQTT connection count should be retained."
    Assert-Equal $mqttTransport.qos 1 "MQTT QoS should be retained."
    Assert-Equal $mqttTransport.applicationPersistenceEvidence "REQUIRES_RECONCILIATION" "MQTT persistence evidence policy should be explicit."

    $runner = Invoke-CapacityRunnerPhase `
        -RunDirectory $scheduled.runDirectory `
        -Phase "generate" `
        -Protocol "rest" `
        -MaxMaterializedReadings 10 `
        -MaxStartedReadings 4 `
        -DryRun
    Assert-Equal $runner.status "INCONCLUSIVE" "guard-stopped dry run should be inconclusive."
    Assert-True (Test-Path -LiteralPath $runner.runnerSummaryPath) "runner-summary.json was not written."
    Assert-True (Test-Path -LiteralPath $runner.cancellationReportPath) "cancellation-report.json was not written."
    $cancellation = Get-Content -Raw -LiteralPath $runner.cancellationReportPath | ConvertFrom-Json
    Assert-Equal $cancellation.status "STOPPED_BY_GUARD" "guard stop should write a cancellation report."
    Assert-Equal $cancellation.guardEvents[0].guard "max-started-readings" "cancellation should retain the stopping guard."

    $preparedRunner = Invoke-CapacityRunnerPhase `
        -RunDirectory $scheduled.runDirectory `
        -Phase "prepare" `
        -DryRun
    Assert-Equal $preparedRunner.status "DRY_RUN" "prepare phase should support dry-run evidence capture."
    $preparedRunnerSummary = Get-Content -Raw -LiteralPath $preparedRunner.runnerSummaryPath | ConvertFrom-Json
    Assert-Equal $preparedRunnerSummary.environmentManifestPath "environment-manifest.json" "prepare should write environment manifest in the run directory."
    Assert-Equal $preparedRunnerSummary.observabilityInventoryPath "observability-inventory.json" "prepare should write observability inventory in the run directory."
    Assert-True (Test-Path -LiteralPath (Join-Path $scheduled.runDirectory "environment-manifest.json")) "environment-manifest.json was not written by prepare."
    Assert-True (Test-Path -LiteralPath (Join-Path $scheduled.runDirectory "observability-inventory.json")) "observability-inventory.json was not written by prepare."

    $diskGuard = Invoke-CapacityRunnerPhase `
        -RunDirectory $scheduled.runDirectory `
        -Phase "generate" `
        -Protocol "mqtt" `
        -MaxMaterializedReadings 5 `
        -MinFreeDiskPercent 101 `
        -FaultTargets @("redis") `
        -DryRun
    Assert-Equal $diskGuard.status "INCONCLUSIVE" "disk guard should stop generation as inconclusive."
    Assert-True (Test-Path -LiteralPath $diskGuard.cancellationReportPath) "disk guard cancellation report was not written."
    $diskCancellation = Get-Content -Raw -LiteralPath $diskGuard.cancellationReportPath | ConvertFrom-Json
    Assert-Equal $diskCancellation.guardEvents[0].guard "min-free-disk-percent" "disk guard should be recorded."
    Assert-Equal $diskCancellation.faultRestorationPath "fault-restoration.json" "guard cancellation should link restoration evidence."
    $restoration = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "fault-restoration.json") | ConvertFrom-Json
    Assert-Equal $restoration.status "PLANNED" "dry-run restoration should be planned."
    Assert-Equal $restoration.targets[0].target "redis" "restoration should be scoped to declared runner targets."

    $visibility = New-CapacityVisibilityObservation -Samples @(
        [PSCustomObject]@{ sequence = 0; observed = $true; latencyMillis = 120 },
        [PSCustomObject]@{ sequence = 1; observed = $false; latencyMillis = $null }
    )
    Assert-Equal $visibility.status "INCONCLUSIVE" "observer timeout should be retained as inconclusive."
    Assert-Equal $visibility.timeoutCount 1 "observer timeout count is wrong."
    $emptyVisibility = New-CapacityVisibilityObservation -Samples @()
    Assert-Equal $emptyVisibility.status "NOT_RUN" "empty visibility samples should be NOT_RUN."
    $writtenVisibility = Write-CapacityVisibilityObservation -RunDirectory $scheduled.runDirectory -Observation $visibility
    Assert-True (Test-Path -LiteralPath $writtenVisibility.visibilityPath) "visibility-observations.json was not written."
    $visibilitySummary = Get-Content -Raw -LiteralPath (Join-Path $scheduled.runDirectory "summary.json") | ConvertFrom-Json
    Assert-Equal $visibilitySummary.verdicts.visibilityObservation "INCONCLUSIVE" "visibility verdict should update summary."

    Write-CapacityGenerationPlan -Plan $restPlan -RunDirectory $scheduled.runDirectory | Out-Null
    Write-CapacityGenerationResult -RunDirectory $scheduled.runDirectory -Result $generation | Out-Null
    $committedCsvPath = Join-Path $scheduled.runDirectory "committed-measurements.csv"
    $resolutionRows = @()
    $resolutionReadings = @($restPlan.readings | Where-Object { @(0, 1, 2, 3) -contains [int64] $_.sequence })
    foreach ($reading in $resolutionReadings) {
        foreach ($metricName in @($reading.metricNames)) {
            $measurement = New-CapacityLogicalMeasurement -Reading $reading -MetricName $metricName
            $resolutionRows += [PSCustomObject]@{
                deviceId = $measurement.deviceId
                sourceId = $measurement.sourceId
                recordedAtOffsetSeconds = $measurement.recordedAtOffsetSeconds
                metricName = $measurement.metricName
                numericValue = $measurement.numericValue
            }
        }
    }
    $resolutionRows | Export-Csv -LiteralPath $committedCsvPath -NoTypeInformation -Encoding UTF8
    $resolvedReconciliation = Invoke-CapacityLogicalReconciliation -RunDirectory $scheduled.runDirectory -CommittedMeasurementsPath $committedCsvPath
    Assert-Equal $resolvedReconciliation.status "PASS" "committed CSV should reconcile accepted plus ambiguous identities."
    $resolvedReconciliationJson = Get-Content -Raw -LiteralPath $resolvedReconciliation.reconciliationPath | ConvertFrom-Json
    Assert-Equal $resolvedReconciliationJson.ambiguousSequenceCount 2 "reconciliation should account for unknown and timeout-after-accept identities."

    $expectedReadings = @($preparedSmoke.schedule.materializedReadings | Select-Object -First 2)
    $committed = @()
    foreach ($reading in $expectedReadings) {
        foreach ($metricName in @($reading.metricNames)) {
            $committed += New-CapacityLogicalMeasurement -Reading $reading -MetricName $metricName
        }
    }
    $reconciliation = Test-CapacityLogicalReconciliation -ExpectedReadings $expectedReadings -CommittedMeasurements $committed
    Assert-Equal $reconciliation.status "PASS" "matching logical keys and values should reconcile."
    $corruptCommitted = @($committed)
    $corruptCommitted[0].numericValue = $corruptCommitted[0].numericValue + 1
    $corrupt = Test-CapacityLogicalReconciliation -ExpectedReadings $expectedReadings -CommittedMeasurements $corruptCommitted
    Assert-Equal $corrupt.status "FAIL" "reconciliation should detect wrong values despite matching counts."
    Assert-Equal $corrupt.wrongValueCount 1 "wrong value count is wrong."
    $duplicateCommitted = @($committed + $committed[0])
    $duplicateResult = Test-CapacityLogicalReconciliation -ExpectedReadings $expectedReadings -CommittedMeasurements $duplicateCommitted
    Assert-Equal $duplicateResult.status "FAIL" "reconciliation should reject duplicate committed logical keys."
    Assert-Equal $duplicateResult.duplicateCommittedCount 1 "duplicate committed key count is wrong."
    $writtenReconciliation = Write-CapacityReconciliation -RunDirectory $scheduled.runDirectory -Reconciliation $reconciliation
    Assert-True (Test-Path -LiteralPath $writtenReconciliation.reconciliationPath) "reconciliation.json was not written."

    $provisioned = Invoke-CapacityFixtureProvisioning `
        -RunDirectory $scheduled.runDirectory `
        -LimitDevices 3 `
        -DryRun
    Assert-Equal $provisioned.status "DRY_RUN" "dry-run provisioning should not call APIs."
    Assert-True (Test-Path -LiteralPath $provisioned.statePath) "provisioning-state.json was not written."
    Assert-True (Test-Path -LiteralPath $provisioned.summaryPath) "provisioning-summary.json was not written."
    $provisioningState = Get-Content -Raw -LiteralPath $provisioned.statePath | ConvertFrom-Json
    Assert-Equal $provisioningState.scope.selectedDeviceCount 3 "provisioning limit was not applied."
    Assert-Equal $provisioningState.dataSources.Count 3 "dry-run provisioning did not prepare one source per selected device."

    $mqttPrepared = New-CapacityFixtureSchedule `
        -ProfileName "current-5" `
        -PhaseName "smoke" `
        -RunId "test-current-5-mqtt-provisioning" `
        -TenantCount 10 `
        -WorkerCount 1 `
        -MaxMaterializedReadings 10
    $mqttScheduled = Write-CapacityFixtureSchedule -Prepared $mqttPrepared -OutputRoot $tempRoot
    $mqttProvisioned = Invoke-CapacityFixtureProvisioning `
        -RunDirectory $mqttScheduled.runDirectory `
        -LimitDevices 3 `
        -SourceProtocol MQTT `
        -DryRun
    Assert-Equal $mqttProvisioned.status "DRY_RUN" "MQTT dry-run provisioning should not call APIs."
    $mqttProvisioningState = Get-Content -Raw -LiteralPath $mqttProvisioned.statePath | ConvertFrom-Json
    Assert-Equal $mqttProvisioningState.dataSources[0].protocol "MQTT" "MQTT provisioning should create MQTT protocol sources."
    $mqttProvisionedPlan = New-CapacityGenerationPlan `
        -RunDirectory $mqttScheduled.runDirectory `
        -Protocol "mqtt" `
        -WorkerIndex 0 `
        -MaxReadings 3
    Assert-Equal $mqttProvisionedPlan.status "PLANNED" "MQTT plan should resolve provisioned MQTT identities."
    Write-CapacityGenerationPlan -Plan $mqttProvisionedPlan -RunDirectory $mqttScheduled.runDirectory | Out-Null
    & (Join-Path $PSScriptRoot "mqtt-generator.ps1") -RunDirectory $mqttScheduled.runDirectory -WorkerIndex 0 -Qos 1 -ConnectionCount 1 -DryRun
    $mqttDryRunResult = Get-Content -Raw -LiteralPath (Join-Path $mqttScheduled.runDirectory "generation-result.json") | ConvertFrom-Json
    Assert-Equal $mqttDryRunResult.status "NOT_RUN" "MQTT dry-run should write a NOT_RUN generation result."
    Assert-Equal $mqttDryRunResult.connectionCount 1 "MQTT dry-run should retain requested connection count."
    Assert-Equal $mqttDryRunResult.qos 1 "MQTT dry-run should retain requested QoS."

    $provisionedRestPlan = New-CapacityGenerationPlan `
        -RunDirectory $scheduled.runDirectory `
        -Protocol "rest" `
        -WorkerIndex 0 `
        -MaxReadings 3
    Assert-Equal $provisionedRestPlan.readings[0].deviceId $provisioningState.devices[0].apiDeviceId "REST plan should use provisioned API device ids."
    Assert-Equal $provisionedRestPlan.readings[0].sourceId $provisioningState.dataSources[0].externalId "REST plan should use provisioned external source ids."
    Assert-Equal $provisionedRestPlan.readings[0].apiDataSourceId $provisioningState.dataSources[0].apiDataSourceId "REST plan should retain API data source ids for token lookup."
    Assert-Equal $provisionedRestPlan.identityResolution.unresolvedReadings 0 "provisioned readings should resolve all selected identities."
    $blockedPartialRestPlan = New-CapacityGenerationPlan `
        -RunDirectory $scheduled.runDirectory `
        -Protocol "rest" `
        -WorkerIndex 0 `
        -MaxReadings 4
    Assert-Equal $blockedPartialRestPlan.status "BLOCKED" "REST plan should block when provisioning state does not cover selected readings."
    Assert-Equal $blockedPartialRestPlan.identityResolution.unresolvedReadings 1 "blocked REST plan should count unresolved identities."

    $cleanup = Invoke-CapacityFixtureCleanup `
        -RunDirectory $scheduled.runDirectory `
        -DryRun
    Assert-Equal $cleanup.status "DRY_RUN_CLEANUP" "dry-run cleanup should update state without API calls."
    $cleanupState = Get-Content -Raw -LiteralPath $cleanup.statePath | ConvertFrom-Json
    Assert-Equal $cleanupState.cleanup.deletedDataSources 3 "cleanup should be scoped to provisioned sources."
    Assert-Equal $cleanupState.cleanup.decommissionedDevices 3 "cleanup should be scoped to provisioned devices."

    $cleanupRunner = Invoke-CapacityRunnerPhase `
        -RunDirectory $scheduled.runDirectory `
        -Phase "cleanup" `
        -DryRun
    Assert-Equal $cleanupRunner.status "DRY_RUN_CLEANUP" "runner cleanup phase should delegate to scoped fixture cleanup."

    $environmentWritten = Write-CapacityEnvironmentManifest -Manifest $environment -OutputRoot $tempRoot
    Assert-True (Test-Path -LiteralPath $environmentWritten.environmentManifestPath) "environment-manifest.json was not written."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Capacity harness tests passed."
