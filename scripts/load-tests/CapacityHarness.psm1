$ErrorActionPreference = "Stop"

function Get-CapacityHarnessRoot {
    return $PSScriptRoot
}

function Get-CapacityRepoRoot {
    return (Resolve-Path (Join-Path (Get-CapacityHarnessRoot) "..\..")).Path
}

function Invoke-CapacityCommand {
    param(
        [Parameter(Mandatory = $true)] [string] $FileName,
        [string[]] $ArgumentList = @(),
        [string] $WorkingDirectory = (Get-CapacityRepoRoot),
        [int] $MaxOutputLines = 80
    )

    $previousLocation = Get-Location
    try {
        Set-Location -LiteralPath $WorkingDirectory
        $output = & $FileName @ArgumentList 2>&1
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        $lines = @($output | ForEach-Object { $_.ToString() })
        return [PSCustomObject]@{
            status = if ($exitCode -eq 0) { "OBSERVED" } else { "UNAVAILABLE" }
            exitCode = $exitCode
            output = @($lines | Select-Object -First $MaxOutputLines)
            truncated = ($lines.Count -gt $MaxOutputLines)
        }
    } catch {
        return [PSCustomObject]@{
            status = "UNAVAILABLE"
            exitCode = $null
            output = @($_.Exception.Message)
            truncated = $false
        }
    } finally {
        Set-Location -LiteralPath $previousLocation
    }
}

function Get-CapacitySha256File {
    param(
        [Parameter(Mandatory = $true)] [string] $Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-CapacityProfileDocument {
    param(
        [string] $ProfilePath = (Join-Path (Get-CapacityHarnessRoot) "profiles.json")
    )

    if (-not (Test-Path -LiteralPath $ProfilePath)) {
        throw "Profile file not found: $ProfilePath"
    }

    $document = Get-Content -Raw -LiteralPath $ProfilePath | ConvertFrom-Json
    if ($document.schemaVersion -ne 1) {
        throw "Unsupported load-test profile schema version: $($document.schemaVersion)"
    }
    return $document
}

function ConvertTo-CapacitySafeValue {
    param(
        [Parameter(Mandatory = $true)] [string] $Key,
        [AllowNull()] [object] $Value
    )

    if ($Key -match "PASSWORD|SECRET|TOKEN|PRIVATE|PUBLIC_KEYS|API_KEY|ACCESS_KEY") {
        return "REDACTED"
    }
    return $Value
}

function Get-CapacityEnvDefaults {
    param(
        [Parameter(Mandatory = $true)] [string[]] $Paths
    )

    $defaults = [ordered]@{}
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) {
            continue
        }
        $content = Get-Content -Raw -LiteralPath $path
        $matches = [regex]::Matches($content, "\$\{(?<key>[A-Z0-9_]+):[-]?(?<value>[^}]+)\}")
        foreach ($match in $matches) {
            $key = $match.Groups["key"].Value
            $value = $match.Groups["value"].Value
            if (-not $defaults.Contains($key)) {
                $defaults[$key] = [PSCustomObject]@{
                    key = $key
                    defaultValue = ConvertTo-CapacitySafeValue -Key $key -Value $value
                    sourcePath = $path
                }
            }
        }
    }
    return $defaults
}

function Get-CapacityDefaultValue {
    param(
        [Parameter(Mandatory = $true)] [object] $Defaults,
        [Parameter(Mandatory = $true)] [string] $Key
    )

    if ($Defaults.Contains($Key)) {
        return $Defaults[$Key].defaultValue
    }
    return "NOT_OBSERVED"
}

function Get-CapacityComposeFiles {
    param(
        [string] $RepoRoot = (Get-CapacityRepoRoot)
    )

    $relativePaths = @(
        "docker-compose.yml",
        "deploy\docker-compose\docker-compose.infra.yml",
        "deploy\docker-compose\docker-compose.app.yml",
        "deploy\docker-compose\docker-compose.observability.yml"
    )
    $files = @()
    foreach ($relativePath in $relativePaths) {
        $path = Join-Path $RepoRoot $relativePath
        $files += [PSCustomObject]@{
            path = $relativePath.Replace("\", "/")
            exists = Test-Path -LiteralPath $path
            sha256 = Get-CapacitySha256File -Path $path
        }
    }
    return @($files)
}

function Get-CapacityKafkaTopicDefaults {
    param(
        [string] $RepoRoot = (Get-CapacityRepoRoot)
    )

    $topicScript = Join-Path $RepoRoot "deploy\docker-compose\init\redpanda\create-topics.sh"
    if (-not (Test-Path -LiteralPath $topicScript)) {
        return [PSCustomObject]@{
            status = "NOT_OBSERVED"
            sourcePath = "deploy/docker-compose/init/redpanda/create-topics.sh"
            defaultPartitions = "NOT_OBSERVED"
            defaultReplicas = "NOT_OBSERVED"
            topics = @()
        }
    }

    $content = Get-Content -Raw -LiteralPath $topicScript
    $partitions = if ($content -match 'PARTITIONS="\$\{KAFKA_TOPIC_PARTITIONS:-(?<value>[^}]+)\}"') { $Matches["value"] } else { "NOT_OBSERVED" }
    $replicas = if ($content -match 'REPLICAS="\$\{KAFKA_TOPIC_REPLICAS:-(?<value>[^}]+)\}"') { $Matches["value"] } else { "NOT_OBSERVED" }
    $topics = @()
    foreach ($line in (Get-Content -LiteralPath $topicScript)) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^[a-z0-9][a-z0-9.-]+\.v1(\.dlq)?$") {
            $topics += $trimmed
        }
    }

    return [PSCustomObject]@{
        status = "OBSERVED"
        sourcePath = "deploy/docker-compose/init/redpanda/create-topics.sh"
        defaultPartitions = $partitions
        defaultReplicas = $replicas
        compactedTopics = @("source.runtime-config.v1")
        topics = @($topics | Select-Object -Unique)
    }
}

function New-CapacityEnvironmentManifest {
    param(
        [string] $RunId = $null,
        [string] $ProfilePath = (Join-Path (Get-CapacityHarnessRoot) "profiles.json")
    )

    if ($null -eq $RunId -or $RunId.Trim().Length -eq 0) {
        $RunId = "env-$((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))"
    }

    $repoRoot = Get-CapacityRepoRoot
    $profileDocument = Get-CapacityProfileDocument -ProfilePath $ProfilePath
    $configPaths = @(
        (Join-Path $repoRoot "docker-compose.yml"),
        (Join-Path $repoRoot "backend\services\api-gateway\src\main\resources\application.yml"),
        (Join-Path $repoRoot "backend\services\ingestion-service\src\main\resources\application.yml"),
        (Join-Path $repoRoot "backend\services\telemetry-service\src\main\resources\application.yml"),
        (Join-Path $repoRoot "backend\services\analytics-service\src\main\resources\application.yml"),
        (Join-Path $repoRoot "backend\services\alert-service\src\main\resources\application.yml")
    )
    $envDefaults = Get-CapacityEnvDefaults -Paths $configPaths

    $gitHead = Invoke-CapacityCommand -FileName "git" -ArgumentList @("rev-parse", "--verify", "HEAD") -MaxOutputLines 1
    $gitBranch = Invoke-CapacityCommand -FileName "git" -ArgumentList @("branch", "--show-current") -MaxOutputLines 1
    $gitStatus = Invoke-CapacityCommand -FileName "git" -ArgumentList @("status", "--porcelain") -MaxOutputLines 200
    $dirtyEntries = if ($gitStatus.status -eq "OBSERVED") { @($gitStatus.output).Count } else { $null }

    $computer = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
    $processor = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
    $repoDriveName = ([System.IO.Path]::GetPathRoot($repoRoot)).TrimEnd("\").TrimEnd(":")
    $repoDrive = Get-PSDrive -Name $repoDriveName -ErrorAction SilentlyContinue

    $dockerVersion = Invoke-CapacityCommand -FileName "docker" -ArgumentList @("--version") -MaxOutputLines 3
    $dockerComposeVersion = Invoke-CapacityCommand -FileName "docker" -ArgumentList @("compose", "version") -MaxOutputLines 3
    $dockerComposePs = Invoke-CapacityCommand -FileName "docker" -ArgumentList @("compose", "ps", "--format", "json") -MaxOutputLines 80

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        sanitization = @{
            secrets = "REDACTED"
            highCardinalityRuntimeIds = "Not collected from running services by this command."
            runtimeMutation = "Read-only; no containers, databases or topics are created."
        }
        git = @{
            commit = if ($gitHead.status -eq "OBSERVED" -and $gitHead.output.Count -gt 0) { $gitHead.output[0] } else { "NOT_OBSERVED" }
            branch = if ($gitBranch.status -eq "OBSERVED" -and $gitBranch.output.Count -gt 0) { $gitBranch.output[0] } else { "NOT_OBSERVED" }
            dirtyEntryCount = if ($null -eq $dirtyEntries) { "NOT_OBSERVED" } else { $dirtyEntries }
            statusObserved = $gitStatus.status
        }
        host = @{
            os = [System.Environment]::OSVersion.VersionString
            processorName = if ($processor) { $processor.Name } else { "NOT_OBSERVED" }
            logicalProcessors = if ($computer) { $computer.NumberOfLogicalProcessors } else { "NOT_OBSERVED" }
            totalMemoryBytes = if ($computer) { [int64] $computer.TotalPhysicalMemory } else { "NOT_OBSERVED" }
            repoDrive = if ($repoDrive) {
                @{
                    name = $repoDrive.Name
                    usedBytes = [int64] $repoDrive.Used
                    freeBytes = [int64] $repoDrive.Free
                }
            } else {
                "NOT_OBSERVED"
            }
        }
        compose = @{
            files = Get-CapacityComposeFiles -RepoRoot $repoRoot
            dockerVersion = $dockerVersion
            dockerComposeVersion = $dockerComposeVersion
            dockerComposePs = $dockerComposePs
        }
        metricCatalog = @{
            profileCatalogNames = @($profileDocument.supportedNumericMetrics)
            resume10Status = "SUPPORTED_BY_PROFILE_CATALOG"
            catalogSource = "scripts/load-tests/profiles.json plus current API docs and service catalogs"
        }
        rateLimits = @{
            gateway = @{
                enabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "GATEWAY_RATE_LIMIT_ENABLED"
                redisEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "GATEWAY_RATE_LIMIT_REDIS_ENABLED"
                authRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "GATEWAY_AUTH_RATE_LIMIT"
                ingestionRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "GATEWAY_INGESTION_RATE_LIMIT"
                generalRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "GATEWAY_GENERAL_RATE_LIMIT"
            }
            ingestion = @{
                enabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_RATE_LIMIT_ENABLED"
                redisEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_RATE_LIMIT_REDIS_ENABLED"
                window = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_RATE_LIMIT_WINDOW"
                sourceRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_SOURCE_REQUESTS_PER_WINDOW"
                deviceRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_DEVICE_REQUESTS_PER_WINDOW"
                accountRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_ACCOUNT_REQUESTS_PER_WINDOW"
                ipRequestsPerWindow = Get-CapacityDefaultValue -Defaults $envDefaults -Key "INGESTION_IP_REQUESTS_PER_WINDOW"
            }
            telemetry = @{
                redisEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "TELEMETRY_RATE_LIMIT_REDIS_ENABLED"
                explicitLimits = "NOT_OBSERVED"
            }
        }
        kafka = Get-CapacityKafkaTopicDefaults -RepoRoot $repoRoot
        retention = @{
            telemetryTimescalePolicyEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "TELEMETRY_TIMESCALE_POLICY_ENABLED"
            analyticsRetentionEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ANALYTICS_RETENTION_ENABLED"
            alertRetentionEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ALERT_RETENTION_ENABLED"
            runtimeDatabasePolicies = "NOT_OBSERVED"
        }
        downstream = @{
            analyticsKafkaConsumeEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ANALYTICS_KAFKA_CONSUME_ENABLED"
            analyticsKafkaPublishEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ANALYTICS_KAFKA_PUBLISH_ENABLED"
            analyticsJobsWorkerEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ANALYTICS_JOBS_WORKER_ENABLED"
            mlTrainingWorkerEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "APP_ML_TRAINING_WORKER_ENABLED"
            alertKafkaConsumeEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ALERT_KAFKA_CONSUME_ENABLED"
            alertNotificationsEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ALERT_NOTIFICATIONS_ENABLED"
            alertNotificationsWorkerEnabled = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ALERT_NOTIFICATIONS_WORKER_ENABLED"
            mlServiceUri = Get-CapacityDefaultValue -Defaults $envDefaults -Key "ML_SERVICE_URI"
        }
        configDefaults = @($envDefaults.Values)
    }
}

function Get-CapacityJavaMeterNames {
    param(
        [string] $RepoRoot = (Get-CapacityRepoRoot)
    )

    $sourceRoots = @(
        "backend\services\ingestion-service\src\main\java",
        "backend\services\telemetry-service\src\main\java"
    )
    $meters = New-Object System.Collections.Generic.List[object]
    foreach ($relativeRoot in $sourceRoots) {
        $root = Join-Path $RepoRoot $relativeRoot
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }
        foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File -Filter "*.java") {
            $content = Get-Content -Raw -LiteralPath $file.FullName
            $patterns = @(
                '(?:counter|timer|summary)\("(?<name>[^"]+)"',
                'Timer\.builder\("(?<name>[^"]+)"',
                'Gauge\.builder\("(?<name>[^"]+)"'
            )
            foreach ($pattern in $patterns) {
                foreach ($match in [regex]::Matches($content, $pattern)) {
                    $meters.Add([PSCustomObject]@{
                        name = $match.Groups["name"].Value
                        sourcePath = $file.FullName.Substring($RepoRoot.Length + 1).Replace("\", "/")
                    })
                }
            }
        }
    }
    return @($meters | Sort-Object -Property name, sourcePath -Unique)
}

function Test-CapacityTextContains {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $Pattern
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    return ((Get-Content -Raw -LiteralPath $Path) -match $Pattern)
}

function New-CapacityObservabilityInventory {
    param(
        [string] $RunId = $null,
        [string] $RepoRoot = (Get-CapacityRepoRoot)
    )

    if ($null -eq $RunId -or $RunId.Trim().Length -eq 0) {
        $RunId = "observability-$((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))"
    }

    $prometheusPath = Join-Path $RepoRoot "deploy\docker-compose\observability\prometheus.yml"
    $ingestionApplicationPath = Join-Path $RepoRoot "backend\services\ingestion-service\src\main\resources\application.yml"
    $telemetryApplicationPath = Join-Path $RepoRoot "backend\services\telemetry-service\src\main\resources\application.yml"
    $observedMeters = Get-CapacityJavaMeterNames -RepoRoot $RepoRoot
    $observedMeterNames = @($observedMeters | Select-Object -ExpandProperty name -Unique)

    $expectedMeters = @(
        "voltcanary.ingestion.processing",
        "voltcanary.ingestion.kafka.publish.latency",
        "voltcanary.ingestion.redis.rate_limit.latency",
        "voltcanary.ingestion.redis.idempotency.latency",
        "voltcanary.ingestion.batch.size",
        "voltcanary.ingestion.readings",
        "voltcanary.ingestion.metrics",
        "voltcanary.telemetry.processing",
        "voltcanary.telemetry.rows",
        "telemetry.outbox.depth",
        "telemetry.outbox.oldest.pending.age.seconds",
        "telemetry.outbox.retry.count",
        "telemetry.outbox.stuck.processing"
    )

    $meterStatus = @($expectedMeters | ForEach-Object {
        $expectedMeter = $_
        [PSCustomObject]@{
            name = $expectedMeter
            status = if ($observedMeterNames -contains $expectedMeter) { "PRESENT" } else { "MISSING" }
            sources = @($observedMeters | Where-Object { $_.name -eq $expectedMeter } | Select-Object -ExpandProperty sourcePath)
        }
    })

    $scrapeJobs = @(
        [PSCustomObject]@{ job = "voltcanary-apps"; target = "api-gateway/identity/device/ingestion/telemetry/analytics/alert actuator prometheus"; requiredFor = "application meters and histograms" },
        [PSCustomObject]@{ job = "redpanda"; target = "redpanda:9644/public_metrics"; requiredFor = "consumer group lag and Kafka broker health" },
        [PSCustomObject]@{ job = "postgres"; target = "postgres-exporter:9187"; requiredFor = "service database resource observations" },
        [PSCustomObject]@{ job = "timescaledb"; target = "timescaledb-exporter:9187"; requiredFor = "telemetry database resource observations" },
        [PSCustomObject]@{ job = "cadvisor"; target = "cadvisor:8080"; requiredFor = "container CPU, memory and filesystem observations" }
    )
    $scrapeStatus = @($scrapeJobs | ForEach-Object {
        [PSCustomObject]@{
            job = $_.job
            target = $_.target
            requiredFor = $_.requiredFor
            status = if (Test-CapacityTextContains -Path $prometheusPath -Pattern "job_name:\s*$($_.job)") { "CONFIGURED" } else { "MISSING" }
        }
    })

    $queries = @(
        [PSCustomObject]@{ name = "ingestion_processing_p95"; promql = "histogram_quantile(0.95, sum(rate(voltcanary_ingestion_processing_seconds_bucket[5m])) by (le))"; purpose = "Ingestion processing p95 from histogram buckets." },
        [PSCustomObject]@{ name = "ingestion_kafka_publish_p95"; promql = "histogram_quantile(0.95, sum(rate(voltcanary_ingestion_kafka_publish_latency_seconds_bucket[5m])) by (le))"; purpose = "Kafka publish latency p95." },
        [PSCustomObject]@{ name = "telemetry_processing_p95"; promql = "histogram_quantile(0.95, sum(rate(voltcanary_telemetry_processing_seconds_bucket[5m])) by (le))"; purpose = "Telemetry persistence processing p95." },
        [PSCustomObject]@{ name = "telemetry_outbox_depth"; promql = "sum(telemetry_outbox_depth) by (status)"; purpose = "Outbox backlog by status." },
        [PSCustomObject]@{ name = "redpanda_consumer_lag"; promql = "sum by (group, topic) (redpanda_kafka_consumer_group_latest_offset - redpanda_kafka_consumer_group_committed_offset)"; purpose = "Consumer group lag; verify exact Redpanda series names from scrape before relying on this query." },
        [PSCustomObject]@{ name = "postgres_connections"; promql = "pg_stat_database_numbackends"; purpose = "Database connection pressure." },
        [PSCustomObject]@{ name = "postgres_deadlocks"; promql = "increase(pg_stat_database_deadlocks[5m])"; purpose = "Database lock failure signal during load." },
        [PSCustomObject]@{ name = "container_cpu"; promql = "sum(rate(container_cpu_usage_seconds_total{name!=''}[5m])) by (container_label_com_docker_compose_service)"; purpose = "Per-service container CPU from cAdvisor." },
        [PSCustomObject]@{ name = "container_memory"; promql = "container_memory_working_set_bytes{name!=''}"; purpose = "Container memory working set from cAdvisor." },
        [PSCustomObject]@{ name = "container_fs_usage"; promql = "container_fs_usage_bytes{name!=''}"; purpose = "Container filesystem usage from cAdvisor." }
    )

    $histogramStatus = @(
        [PSCustomObject]@{
            service = "ingestion-service"
            status = if (Test-CapacityTextContains -Path $ingestionApplicationPath -Pattern "percentiles-histogram:[\s\S]*voltcanary\.ingestion\.processing:\s*true") { "CONFIGURED" } else { "MISSING" }
        },
        [PSCustomObject]@{
            service = "telemetry-service"
            status = if (Test-CapacityTextContains -Path $telemetryApplicationPath -Pattern "percentiles-histogram:[\s\S]*voltcanary\.telemetry\.processing:\s*true") { "CONFIGURED" } else { "MISSING" }
        }
    )

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = if ((@($meterStatus | Where-Object { $_.status -eq "MISSING" }).Count -eq 0) -and (@($scrapeStatus | Where-Object { $_.status -eq "MISSING" }).Count -eq 0) -and (@($histogramStatus | Where-Object { $_.status -eq "MISSING" }).Count -eq 0)) { "PASS" } else { "INCONCLUSIVE" }
        observedMeters = @($observedMeters)
        expectedMeters = @($meterStatus)
        histogramBuckets = @($histogramStatus)
        scrapeJobs = @($scrapeStatus)
        queries = @($queries)
        notes = @(
            "Inventory is source/config based and read-only.",
            "Redpanda metric names can vary by version; confirm exact lag series in a live scrape.",
            "Do not average replica percentiles; aggregate histogram buckets first."
        )
    }
}

function Write-CapacityObservabilityInventory {
    param(
        [Parameter(Mandatory = $true)] [object] $Inventory,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Inventory -Path $paths.observabilityInventoryPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary -NotePropertyName observabilityInventoryPath -NotePropertyValue "observability-inventory.json" -Force
        Add-Member -InputObject $summary -NotePropertyName observabilityStatus -NotePropertyValue $Inventory.status -Force
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Inventory.status
        observabilityInventoryPath = $paths.observabilityInventoryPath
    }
}

function Write-CapacityEnvironmentManifest {
    param(
        [Parameter(Mandatory = $true)] [object] $Manifest,
        [string] $OutputRoot = (Join-Path (Get-CapacityRepoRoot) "artifacts\load-tests")
    )

    $runDirectory = Join-Path $OutputRoot $Manifest.runId
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $manifestPath = Join-Path $runDirectory "environment-manifest.json"
    $Manifest | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    return [PSCustomObject]@{
        runDirectory = $runDirectory
        environmentManifestPath = $manifestPath
    }
}

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)] [object] $Object,
        [Parameter(Mandatory = $true)] [string] $Name,
        [object] $Default = $null
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $Default
    }
    return $property.Value
}

function Get-NamedProperty {
    param(
        [Parameter(Mandatory = $true)] [object] $Object,
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Kind
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Unknown $Kind '$Name'."
    }
    return $property.Value
}

function Get-CapacityWorkloadArithmetic {
    param(
        [Parameter(Mandatory = $true)] [object] $Profile,
        [Parameter(Mandatory = $true)] [object] $Phase
    )

    $deviceCount = [int] (Get-PropertyValue -Object $Phase -Name "deviceCount" -Default $Profile.deviceCount)
    $intervalSeconds = [int] (Get-PropertyValue -Object $Phase -Name "intervalSeconds" -Default $Profile.intervalSeconds)
    $measurementSeconds = [int] $Phase.measurementSeconds
    $warmupSeconds = [int] (Get-PropertyValue -Object $Phase -Name "warmupSeconds" -Default 0)
    $drainSeconds = [int] (Get-PropertyValue -Object $Phase -Name "drainSeconds" -Default 0)
    $requiredMetricCount = [int] $Profile.requiredNumericMetricCount
    $batchSize = [int] (Get-PropertyValue -Object $Profile -Name "batchSize" -Default 1)
    $rateMultiplier = [double] (Get-PropertyValue -Object $Phase -Name "rateMultiplier" -Default 1.0)

    if ($deviceCount -le 0) { throw "deviceCount must be positive." }
    if ($intervalSeconds -le 0) { throw "intervalSeconds must be positive." }
    if ($measurementSeconds -le 0) { throw "measurementSeconds must be positive." }
    if ($requiredMetricCount -le 0) { throw "requiredNumericMetricCount must be positive." }
    if ($batchSize -le 0) { throw "batchSize must be positive." }
    if ($rateMultiplier -le 0) { throw "rateMultiplier must be positive." }

    $effectiveIntervalSeconds = [double] $intervalSeconds / $rateMultiplier
    $scheduledCycles = [math]::Floor($measurementSeconds / $effectiveIntervalSeconds)
    $scheduledReadings = [int64] ($deviceCount * $scheduledCycles)
    $expectedNumericValues = [int64] ($scheduledReadings * $requiredMetricCount)
    $estimatedRequests = [int64] [math]::Ceiling($scheduledReadings / $batchSize)

    $averageReadingsPerSecond = if ($measurementSeconds -eq 0) { 0 } else { $scheduledReadings / $measurementSeconds }
    $averageNumericValuesPerSecond = if ($measurementSeconds -eq 0) { 0 } else { $expectedNumericValues / $measurementSeconds }

    $burstWindowSeconds = Get-PropertyValue -Object $Phase -Name "burstWindowSeconds" -Default $null
    $burstReadingsPerSecond = $null
    if ($null -ne $burstWindowSeconds) {
        $burstWindowSeconds = [int] $burstWindowSeconds
        if ($burstWindowSeconds -le 0) { throw "burstWindowSeconds must be positive." }
        $burstReadingsPerSecond = $deviceCount / $burstWindowSeconds
    }
    $readMix = Get-PropertyValue -Object $Phase -Name "readMix" -Default $null
    $readWorkload = $null
    if ($null -ne $readMix) {
        $latestPerSecond = [double] (Get-PropertyValue -Object $readMix -Name "latestPerSecond" -Default 0)
        $chartPerSecond = [double] (Get-PropertyValue -Object $readMix -Name "chartPerSecond" -Default 0)
        $exportPerMinute = [double] (Get-PropertyValue -Object $readMix -Name "exportPerMinute" -Default 0)
        $readWorkload = [PSCustomObject]@{
            status = "PLANNED_NOT_EXECUTED_BY_INGESTION_GENERATOR"
            latestRequests = [int64] [math]::Floor($latestPerSecond * $measurementSeconds)
            chartRequests = [int64] [math]::Floor($chartPerSecond * $measurementSeconds)
            exportRequests = [int64] [math]::Floor($exportPerMinute * ($measurementSeconds / 60.0))
            note = "Read workload is accounted separately from ingestion scheduled readings and numeric values."
        }
        Add-Member -InputObject $readWorkload -NotePropertyName totalReadRequests -NotePropertyValue ([int64] ($readWorkload.latestRequests + $readWorkload.chartRequests + $readWorkload.exportRequests)) -Force
    }
    $phaseKind = if ($null -ne $readMix) {
        "mixed"
    } elseif ($null -ne $burstWindowSeconds) {
        "burst"
    } elseif ($rateMultiplier -ne 1.0) {
        "stress"
    } else {
        "periodic"
    }

    return [PSCustomObject]@{
        deviceCount = $deviceCount
        intervalSeconds = $intervalSeconds
        effectiveIntervalSeconds = [math]::Round($effectiveIntervalSeconds, 6)
        phaseKind = $phaseKind
        measurementSeconds = $measurementSeconds
        warmupSeconds = $warmupSeconds
        drainSeconds = $drainSeconds
        scheduledCycles = [int64] $scheduledCycles
        scheduledReadings = $scheduledReadings
        expectedNumericValues = $expectedNumericValues
        requiredNumericMetricCount = $requiredMetricCount
        batchSize = $batchSize
        estimatedRequests = $estimatedRequests
        averageReadingsPerSecond = [math]::Round($averageReadingsPerSecond, 6)
        averageNumericValuesPerSecond = [math]::Round($averageNumericValuesPerSecond, 6)
        burstWindowSeconds = $burstWindowSeconds
        burstReadingsPerSecond = if ($null -eq $burstReadingsPerSecond) { $null } else { [math]::Round($burstReadingsPerSecond, 6) }
        rateMultiplier = $rateMultiplier
        readWorkload = $readWorkload
    }
}

function New-CapacityStableHex {
    param(
        [Parameter(Mandatory = $true)] [string] $Value,
        [int] $Length = 32
    )

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($bytes)
        $hex = [System.BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()
        return $hex.Substring(0, [math]::Min($Length, $hex.Length))
    } finally {
        $sha.Dispose()
    }
}

function New-CapacityStableGuid {
    param(
        [Parameter(Mandatory = $true)] [string] $Value
    )

    $hex = New-CapacityStableHex -Value $Value -Length 32
    return "$($hex.Substring(0, 8))-$($hex.Substring(8, 4))-$($hex.Substring(12, 4))-$($hex.Substring(16, 4))-$($hex.Substring(20, 12))"
}

function Get-CapacityWorkerRanges {
    param(
        [Parameter(Mandatory = $true)] [int64] $ScheduledReadings,
        [Parameter(Mandatory = $true)] [int] $WorkerCount
    )

    if ($WorkerCount -le 0) { throw "WorkerCount must be positive." }

    $ranges = @()
    $base = [int64] [math]::Floor([double] $ScheduledReadings / [double] $WorkerCount)
    $remainder = [int64] ($ScheduledReadings % $WorkerCount)
    $start = [int64] 0

    for ($workerIndex = 0; $workerIndex -lt $WorkerCount; $workerIndex++) {
        $count = [int64] $base
        if ($workerIndex -lt $remainder) {
            $count += 1
        }
        $end = [int64] ($start + $count)
        $ranges += [PSCustomObject]@{
            workerIndex = $workerIndex
            startSequenceInclusive = $start
            endSequenceExclusive = $end
            scheduledReadings = $count
        }
        $start = $end
    }

    return @($ranges)
}

function Get-CapacityWorkerIndexForSequence {
    param(
        [Parameter(Mandatory = $true)] [int64] $Sequence,
        [Parameter(Mandatory = $true)] [object[]] $WorkerRanges
    )

    foreach ($range in $WorkerRanges) {
        if ($Sequence -ge $range.startSequenceInclusive -and $Sequence -lt $range.endSequenceExclusive) {
            return [int] $range.workerIndex
        }
    }
    throw "Sequence $Sequence is outside worker ranges."
}

function New-CapacityStorageFixturePlan {
    param(
        [ValidateSet("current-5", "resume-10")] [string] $ProfileName = "current-5",
        [ValidateSet("import-api", "storage-only-sql")] [string] $Mode = "storage-only-sql",
        [string] $RunId = $null,
        [int] $TotalDays = 30,
        [int] $WindowHours = 6,
        [int64] $PilotValues = 1000000,
        [int64] $MeasuredPilotBytes = 0,
        [string] $ProfilePath = (Join-Path (Get-CapacityHarnessRoot) "profiles.json")
    )

    if ($TotalDays -le 0) { throw "TotalDays must be positive." }
    if ($WindowHours -le 0) { throw "WindowHours must be positive." }
    if ($PilotValues -lt 0) { throw "PilotValues cannot be negative." }
    if ($MeasuredPilotBytes -lt 0) { throw "MeasuredPilotBytes cannot be negative." }
    if ($null -eq $RunId -or $RunId.Trim().Length -eq 0) {
        $RunId = "storage-$((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))-$ProfileName-$Mode"
    }

    $preflight = New-CapacityPreflight -ProfileName $ProfileName -PhaseName "daily" -RunId $RunId -ProfilePath $ProfilePath
    if ($preflight.status -eq "BLOCKED") {
        return [PSCustomObject]@{
            schemaVersion = 1
            runId = $RunId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            status = "BLOCKED"
            profile = $ProfileName
            mode = $Mode
            blockers = @($preflight.blockers)
            note = "Storage fixture is blocked because the selected workload profile is unsupported."
        }
    }

    $dailyReadings = [int64] $preflight.workload.scheduledReadings
    $dailyValues = [int64] $preflight.workload.expectedNumericValues
    $totalReadings = [int64] ($dailyReadings * $TotalDays)
    $totalValues = [int64] ($dailyValues * $TotalDays)
    $windowCount = [int] [math]::Ceiling(($TotalDays * 24.0) / $WindowHours)
    $baseValuesPerWindow = [int64] [math]::Floor([double] $totalValues / [double] $windowCount)
    $valueRemainder = [int64] ($totalValues % $windowCount)
    $baseReadingsPerWindow = [int64] [math]::Floor([double] $totalReadings / [double] $windowCount)
    $readingRemainder = [int64] ($totalReadings % $windowCount)

    $windows = @()
    $valueStart = [int64] 0
    for ($i = 0; $i -lt $windowCount; $i++) {
        $valueCount = $baseValuesPerWindow
        if ($i -lt $valueRemainder) { $valueCount += 1 }
        $readingCount = $baseReadingsPerWindow
        if ($i -lt $readingRemainder) { $readingCount += 1 }
        $windowStartHours = [int64] ($i * $WindowHours)
        $windowEndHours = [int64] [math]::Min($TotalDays * 24, ($i + 1) * $WindowHours)
        $windows += [PSCustomObject]@{
            windowIndex = $i
            startOffsetHours = $windowStartHours
            endOffsetHours = $windowEndHours
            expectedReadings = $readingCount
            expectedNumericValues = $valueCount
            valueStartInclusive = $valueStart
            valueEndExclusive = [int64] ($valueStart + $valueCount)
            checksumSeed = New-CapacityStableHex -Value "$RunId`:$ProfileName`:$Mode`:$i`:$valueStart`:$valueCount" -Length 16
            status = "PENDING"
        }
        $valueStart += $valueCount
    }

    $bytesPerValue = if ($MeasuredPilotBytes -gt 0 -and $PilotValues -gt 0) {
        [math]::Round($MeasuredPilotBytes / [double] $PilotValues, 6)
    } else {
        280.0
    }
    $estimatedBytes = [int64] [math]::Ceiling($totalValues * $bytesPerValue)
    $pilotStatus = if ($MeasuredPilotBytes -gt 0) { "MEASURED_PILOT" } else { "ESTIMATE_UNTIL_PILOT_MEASURED" }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = "PLANNED"
        profile = $ProfileName
        mode = $Mode
        storageClaimStatus = "UNVERIFIED_UNTIL_FULL_VOLUME_LOADED_AND_QUERIED"
        explicitScope = if ($Mode -eq "storage-only-sql") {
            "Storage-only SQL fixture; does not prove ingestion, Kafka, outbox, analytics or alert capacity."
        } else {
            "Import API fixture; exercises import semantics but not realtime ingestion throughput."
        }
        targetDistribution = @{
            days = $TotalDays
            windowHours = $WindowHours
            windowCount = $windowCount
            dailyReadings = $dailyReadings
            dailyNumericValues = $dailyValues
            totalReadings = $totalReadings
            totalNumericValues = $totalValues
            metricNames = @($preflight.profileMetricNames)
        }
        diskEstimate = @{
            status = $pilotStatus
            pilotValues = $PilotValues
            measuredPilotBytes = $MeasuredPilotBytes
            bytesPerValue = $bytesPerValue
            estimatedBytes = $estimatedBytes
            estimatedGiB = [math]::Round($estimatedBytes / 1GB, 3)
            includes = @("telemetry.measurements", "selected indexes", "quality/raw/outbox allowance")
            omissions = if ($MeasuredPilotBytes -gt 0) { @() } else { @("WAL amplification", "temporary sort/build space", "exact TOAST/page fill behavior") }
        }
        sqlFixture = @{
            table = "telemetry.measurements"
            requiredColumns = @("device_id", "source_id", "recorded_at", "metric_name", "metric_value", "unit", "quality", "received_at", "created_at", "account_id")
            conflictKey = "(device_id, source_id, recorded_at, metric_name)"
            sampleInsert = "insert into telemetry.measurements (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at, account_id) values (...) on conflict (device_id, source_id, recorded_at, metric_name) do nothing"
        }
        importFixture = @{
            endpoint = "/ingestion/imports/readings"
            semantics = "Historical import mode; expected to preserve validation and idempotency but not realtime ingestion throughput."
        }
        checkpoints = @($windows)
        validation = @{
            expectedCheckpointCount = $windowCount
            expectedNumericValues = $totalValues
            expectedReadings = $totalReadings
            checksumPolicy = "Each window records deterministic checksumSeed; loaders must persist row counts and checksums before marking a window COMPLETE."
        }
    }
}

function Test-CapacityStorageFixtureCheckpoint {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan
    )

    if ($Plan.status -eq "BLOCKED") {
        return [PSCustomObject]@{
            status = "BLOCKED"
            findings = @([PSCustomObject]@{ status = "BLOCKED"; check = "profile"; detail = "Selected profile is blocked." })
        }
    }

    $findings = New-Object System.Collections.Generic.List[object]
    $valueSum = [int64] ((@($Plan.checkpoints) | Measure-Object -Property expectedNumericValues -Sum).Sum)
    $readingSum = [int64] ((@($Plan.checkpoints) | Measure-Object -Property expectedReadings -Sum).Sum)
    if ($valueSum -ne [int64] $Plan.targetDistribution.totalNumericValues) {
        $findings.Add([PSCustomObject]@{ status = "FAIL"; check = "value-distribution"; detail = "Checkpoint values $valueSum do not match target $($Plan.targetDistribution.totalNumericValues)." })
    }
    if ($readingSum -ne [int64] $Plan.targetDistribution.totalReadings) {
        $findings.Add([PSCustomObject]@{ status = "FAIL"; check = "reading-distribution"; detail = "Checkpoint readings $readingSum do not match target $($Plan.targetDistribution.totalReadings)." })
    }
    $invalidWindows = @($Plan.checkpoints | Where-Object { [int64] $_.valueEndExclusive -le [int64] $_.valueStartInclusive -or [int] $_.endOffsetHours -le [int] $_.startOffsetHours })
    if ($invalidWindows.Count -gt 0) {
        $findings.Add([PSCustomObject]@{ status = "FAIL"; check = "checkpoint-window-shape"; detail = "$($invalidWindows.Count) checkpoint window(s) have invalid ranges." })
    }
    $checksumMissing = @($Plan.checkpoints | Where-Object { $null -eq $_.checksumSeed -or $_.checksumSeed.Trim().Length -eq 0 })
    if ($checksumMissing.Count -gt 0) {
        $findings.Add([PSCustomObject]@{ status = "FAIL"; check = "checkpoint-checksum"; detail = "$($checksumMissing.Count) checkpoint window(s) are missing checksum seeds." })
    }
    if ($findings.Count -eq 0) {
        $findings.Add([PSCustomObject]@{ status = "PASS"; check = "checkpoint-distribution"; detail = "Checkpoint windows cover the requested storage fixture distribution." })
    }

    return [PSCustomObject]@{
        status = Get-CapacityClaimStatus -Verdicts @($findings | ForEach-Object { $_.status })
        findings = @($findings.ToArray())
    }
}

function Write-CapacityStorageFixturePlan {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Plan -Path $paths.storageFixturePath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary -NotePropertyName storageFixturePath -NotePropertyValue "storage-fixture.json" -Force
        Add-Member -InputObject $summary -NotePropertyName storageFixtureStatus -NotePropertyValue $Plan.status -Force
        if ($Plan.status -eq "PLANNED") {
            $summary.verdicts.monthlyVolume = "INCONCLUSIVE"
        } elseif ($Plan.status -eq "BLOCKED") {
            $summary.verdicts.monthlyVolume = "BLOCKED"
        }
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Plan.status
        storageFixturePath = $paths.storageFixturePath
    }
}

function New-CapacityMonthlyTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [int] $ColdQueryRepeats = 3,
        [int] $WarmQueryRepeats = 5,
        [int] $LiveWriteSeconds = 300
    )

    if ($ColdQueryRepeats -le 0) { throw "ColdQueryRepeats must be positive." }
    if ($WarmQueryRepeats -le 0) { throw "WarmQueryRepeats must be positive." }
    if ($LiveWriteSeconds -le 0) { throw "LiveWriteSeconds must be positive." }

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $storageFixture = Read-CapacityJsonFile -Path $paths.storageFixturePath -Default $null
    if ($null -eq $storageFixture) {
        return [PSCustomObject]@{
            schemaVersion = 1
            runId = Split-Path -Leaf $RunDirectory
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            status = "BLOCKED"
            blockers = @("storage-fixture.json is required before planning the monthly query/write trial.")
        }
    }
    if ($storageFixture.status -eq "BLOCKED") {
        return [PSCustomObject]@{
            schemaVersion = 1
            runId = $storageFixture.runId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            status = "BLOCKED"
            profile = $storageFixture.profile
            blockers = @($storageFixture.blockers)
        }
    }

    $fullVolumeLoaded = ($storageFixture.PSObject.Properties["loadedNumericValues"] -and ([int64] $storageFixture.loadedNumericValues -eq [int64] $storageFixture.targetDistribution.totalNumericValues))
    $schemaInventoryStatus = if ($storageFixture.PSObject.Properties["schemaInventoryStatus"]) { $storageFixture.schemaInventoryStatus } else { "REQUIRED_AT_EXECUTION" }
    $integrityStatus = if ($storageFixture.PSObject.Properties["integrityStatus"]) { $storageFixture.integrityStatus } else { "REQUIRED_AT_EXECUTION" }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $storageFixture.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = if ($fullVolumeLoaded) { "PLANNED" } else { "INCONCLUSIVE" }
        profile = $storageFixture.profile
        fixtureMode = $storageFixture.mode
        monthlyClaimStatus = if ($fullVolumeLoaded) { "READY_FOR_MEASUREMENT" } else { "UNVERIFIED_UNTIL_FULL_VOLUME_LOADED" }
        requiredInventory = @(
            [PSCustomObject]@{ name = "hypertable-and-chunks"; source = "timescaledb catalog"; status = $schemaInventoryStatus },
            [PSCustomObject]@{ name = "indexes"; source = "pg_indexes and pg_stat_user_indexes"; status = $schemaInventoryStatus },
            [PSCustomObject]@{ name = "retention-and-compression-policies"; source = "timescaledb information views"; status = $schemaInventoryStatus },
            [PSCustomObject]@{ name = "table-index-auxiliary-size"; source = "pg_total_relation_size family"; status = $schemaInventoryStatus }
        )
        dataIntegrity = @{
            expectedNumericValues = [int64] $storageFixture.targetDistribution.totalNumericValues
            loadedNumericValues = if ($storageFixture.PSObject.Properties["loadedNumericValues"]) { [int64] $storageFixture.loadedNumericValues } else { 0 }
            status = $integrityStatus
            checkpointPolicy = "Every storage checkpoint must have count and checksum evidence before monthly query/write evidence can pass."
        }
        queryPhases = @(
            [PSCustomObject]@{ name = "cold"; repeats = $ColdQueryRepeats; cachePolicy = "restart or explicitly clear supported caches before first sample"; verdict = "NOT_RUN" },
            [PSCustomObject]@{ name = "warm"; repeats = $WarmQueryRepeats; cachePolicy = "repeat same supported query mix after warmup"; verdict = "NOT_RUN" }
        )
        supportedQueryMix = @(
            [PSCustomObject]@{ name = "device-range-raw"; purpose = "Raw measurements for one device/source over bounded time range." },
            [PSCustomObject]@{ name = "metric-aggregate-hourly"; purpose = "Time-bucket aggregate for one metric across a bounded cohort." },
            [PSCustomObject]@{ name = "latest-by-device"; purpose = "Latest values for sampled devices." }
        )
        liveWriteTrial = @{
            durationSeconds = $LiveWriteSeconds
            policy = "Run supported writes against the populated dataset while query phases collect latency/error evidence."
            verdict = "NOT_RUN"
        }
        blockers = if ($fullVolumeLoaded) { @() } else { @("Full-volume fixture has not recorded loadedNumericValues equal to targetDistribution.totalNumericValues.") }
    }
}

function Write-CapacityMonthlyTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Plan -Path $paths.monthlyTrialPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary -NotePropertyName monthlyTrialPath -NotePropertyValue "monthly-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName monthlyTrialStatus -NotePropertyValue $Plan.status -Force
        $summary.verdicts.monthlyVolume = if ($Plan.status -eq "BLOCKED") { "BLOCKED" } else { "INCONCLUSIVE" }
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Plan.status
        monthlyTrialPath = $paths.monthlyTrialPath
    }
}

function New-CapacityScalingTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [int[]] $ReplicaCounts = @(1, 2, 3),
        [int] $HpaSampleSeconds = 30
    )

    if ($ReplicaCounts.Count -eq 0) { throw "ReplicaCounts must contain at least one value." }
    if ($HpaSampleSeconds -le 0) { throw "HpaSampleSeconds must be positive." }
    $invalidReplicas = @($ReplicaCounts | Where-Object { $_ -le 0 })
    if ($invalidReplicas.Count -gt 0) { throw "ReplicaCounts must be positive." }

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    $environment = Read-CapacityJsonFile -Path $paths.environmentManifestPath -Default $null
    $observability = Read-CapacityJsonFile -Path $paths.observabilityInventoryPath -Default $null
    $runId = if ($null -eq $summary) { Split-Path -Leaf $RunDirectory } else { $summary.runId }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = "PLANNED"
        fixedReplicaRuns = @($ReplicaCounts | Sort-Object -Unique | ForEach-Object {
            [PSCustomObject]@{
                replicas = [int] $_
                resourcesPerReplica = "RECORD_AT_EXECUTION"
                totalResources = "DERIVE_FROM_RESOURCES_PER_REPLICA_AND_REPLICAS"
                workload = if ($null -eq $summary) { "RECORD_AT_EXECUTION" } else { "$($summary.profile)/$($summary.phase)" }
                partitions = if ($null -eq $environment) { "RECORD_AT_EXECUTION" } else { $environment.kafka.defaultPartitions }
                verdict = "NOT_RUN"
            }
        })
        hpaTimeline = @{
            status = "NOT_RUN"
            sampleSeconds = $HpaSampleSeconds
            requiredEvents = @("scale-target", "replica-count", "pod-readiness", "assignment", "lag", "resource-utilization")
            policy = "HPA evidence is separate from fixed-replica sustainable-throughput comparisons."
        }
        comparisonPolicy = "Compare achieved sustained throughput at fixed thresholds, fixed shared dependencies, same dataset, same seed and same rate limits; do not infer success from replica count."
        evidenceInputs = @{
            environmentManifest = if ($null -eq $environment) { "MISSING" } else { "environment-manifest.json" }
            observabilityInventory = if ($null -eq $observability) { "MISSING" } else { "observability-inventory.json" }
        }
        blockers = @()
    }
}

function Write-CapacityScalingTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Plan -Path $paths.scalingTrialPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary -NotePropertyName scalingTrialPath -NotePropertyValue "scaling-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName scalingTrialStatus -NotePropertyValue $Plan.status -Force
        $summary.verdicts.scaling = "INCONCLUSIVE"
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Plan.status
        scalingTrialPath = $paths.scalingTrialPath
    }
}

function New-CapacityRecoveryTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string[]] $FaultTargets = @("consumer", "redis", "kafka", "database"),
        [int] $OutageSeconds = 60,
        [int] $DrainSeconds = 300
    )

    if ($OutageSeconds -le 0) { throw "OutageSeconds must be positive." }
    if ($DrainSeconds -le 0) { throw "DrainSeconds must be positive." }
    $targets = @($FaultTargets | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -Unique)
    if ($targets.Count -eq 0) { throw "FaultTargets must contain at least one value." }

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    $runId = if ($null -eq $summary) { Split-Path -Leaf $RunDirectory } else { $summary.runId }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = "PLANNED"
        scope = "Bounded single-fault recovery trials; single-process restarts do not prove replicated HA, backup restore or RPO=0."
        trials = @($targets | ForEach-Object {
            [PSCustomObject]@{
                target = $_
                outageSeconds = $OutageSeconds
                drainSeconds = $DrainSeconds
                injection = "REQUIRES_OPERATOR_EXECUTION"
                restoration = "RESTORE_ONLY_THIS_TARGET_IN_FINALLY"
                finalReconciliation = "REQUIRED"
                verdict = "NOT_RUN"
            }
        })
        acceptancePolicy = "Accepted readings before and during the fault must reconcile after bounded recovery/drain before any recovery claim can pass."
        blockers = @()
    }
}

function Write-CapacityRecoveryTrialPlan {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Plan -Path $paths.recoveryTrialPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary -NotePropertyName recoveryTrialPath -NotePropertyValue "recovery-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName recoveryTrialStatus -NotePropertyValue $Plan.status -Force
        $summary.verdicts.recovery = "INCONCLUSIVE"
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Plan.status
        recoveryTrialPath = $paths.recoveryTrialPath
    }
}

function New-CapacityFixtureSchedule {
    param(
        [Parameter(Mandatory = $true)] [string] $ProfileName,
        [Parameter(Mandatory = $true)] [string] $PhaseName,
        [string] $RunId = $null,
        [int] $TenantCount = 100,
        [int] $WorkerCount = 1,
        [int] $MaxMaterializedReadings = 0,
        [string] $ProfilePath = (Join-Path (Get-CapacityHarnessRoot) "profiles.json")
    )

    if ($TenantCount -le 0) { throw "TenantCount must be positive." }
    if ($MaxMaterializedReadings -lt 0) { throw "MaxMaterializedReadings cannot be negative." }

    $preflight = New-CapacityPreflight -ProfileName $ProfileName -PhaseName $PhaseName -RunId $RunId -ProfilePath $ProfilePath
    if ($preflight.status -eq "BLOCKED") {
        return [PSCustomObject]@{
            status = "BLOCKED"
            preflight = $preflight
            fixture = $null
            schedule = $null
        }
    }

    $deviceCount = [int] $preflight.workload.deviceCount
    $effectiveTenantCount = [math]::Min($TenantCount, $deviceCount)
    $devices = [System.Collections.Generic.List[object]]::new()
    $tenants = [System.Collections.Generic.List[object]]::new()

    $baseDevicesPerTenant = [math]::Floor($deviceCount / $effectiveTenantCount)
    $deviceRemainder = $deviceCount % $effectiveTenantCount
    $deviceIndex = 0

    for ($tenantIndex = 0; $tenantIndex -lt $effectiveTenantCount; $tenantIndex++) {
        $tenantDeviceCount = [int] $baseDevicesPerTenant
        if ($tenantIndex -lt $deviceRemainder) {
            $tenantDeviceCount += 1
        }

        $tenantId = New-CapacityStableGuid -Value "$($preflight.runId):tenant:$tenantIndex"
        $tenants.Add([PSCustomObject]@{
            tenantIndex = $tenantIndex
            accountId = $tenantId
            deviceCount = $tenantDeviceCount
        })

        for ($tenantDeviceIndex = 0; $tenantDeviceIndex -lt $tenantDeviceCount; $tenantDeviceIndex++) {
            $deviceId = New-CapacityStableGuid -Value "$($preflight.runId):device:$deviceIndex"
            $sourceId = New-CapacityStableGuid -Value "$($preflight.runId):source:$deviceIndex"
            $devices.Add([PSCustomObject]@{
                deviceIndex = $deviceIndex
                tenantIndex = $tenantIndex
                accountId = $tenantId
                deviceId = $deviceId
                sourceId = $sourceId
                deviceName = "capacity-device-$("{0:D5}" -f $deviceIndex)"
                sourceName = "capacity-source-$("{0:D5}" -f $deviceIndex)"
            })
            $deviceIndex += 1
        }
    }

    $workerRanges = Get-CapacityWorkerRanges -ScheduledReadings $preflight.workload.scheduledReadings -WorkerCount $WorkerCount
    $materializedCount = if ($MaxMaterializedReadings -eq 0) {
        [int64] $preflight.workload.scheduledReadings
    } else {
        [math]::Min([int64] $MaxMaterializedReadings, [int64] $preflight.workload.scheduledReadings)
    }
    $readings = [System.Collections.Generic.List[object]]::new()
    $phaseWindowSeconds = [int] (Get-PropertyValue -Object $preflight.workload -Name "burstWindowSeconds" -Default $preflight.workload.effectiveIntervalSeconds)
    if ($phaseWindowSeconds -le 0) {
        $phaseWindowSeconds = [int] $preflight.workload.intervalSeconds
    }
    $effectiveIntervalSeconds = [double] (Get-PropertyValue -Object $preflight.workload -Name "effectiveIntervalSeconds" -Default $preflight.workload.intervalSeconds)

    for ($sequence = [int64] 0; $sequence -lt $materializedCount; $sequence++) {
        $currentDeviceIndex = [int] ($sequence % $deviceCount)
        $cycleIndex = [int64] [math]::Floor($sequence / $deviceCount)
        $phaseOffsetSeconds = [int] [math]::Floor($currentDeviceIndex * $phaseWindowSeconds / $deviceCount)
        $recordedAtOffsetSeconds = [int64] [math]::Floor(($cycleIndex * $effectiveIntervalSeconds) + $phaseOffsetSeconds)
        $device = $devices[$currentDeviceIndex]
        $readings.Add([PSCustomObject]@{
            sequence = $sequence
            workerIndex = Get-CapacityWorkerIndexForSequence -Sequence $sequence -WorkerRanges $workerRanges
            cycleIndex = $cycleIndex
            deviceIndex = $currentDeviceIndex
            accountId = $device.accountId
            deviceId = $device.deviceId
            sourceId = $device.sourceId
            recordedAtOffsetSeconds = $recordedAtOffsetSeconds
            phaseOffsetSeconds = $phaseOffsetSeconds
            metricNames = $preflight.profileMetricNames
        })
    }

    $fixture = [PSCustomObject]@{
        schemaVersion = 1
        runId = $preflight.runId
        profile = $preflight.profile
        phase = $preflight.phase
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        tenantCount = $effectiveTenantCount
        deviceCount = $deviceCount
        sourceCount = $deviceCount
        tenants = @($tenants)
        devices = @($devices)
        secretPolicy = "No source tokens, credentials or bearer values are written by this fixture manifest."
    }

    $schedule = [PSCustomObject]@{
        schemaVersion = 1
        runId = $preflight.runId
        profile = $preflight.profile
        phase = $preflight.phase
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        workload = $preflight.workload
        workerCount = $WorkerCount
        workerRanges = @($workerRanges)
        materializedReadings = @($readings)
        materializedReadingCount = $materializedCount
        scheduleComplete = ($materializedCount -eq $preflight.workload.scheduledReadings)
        phaseWindowSeconds = $phaseWindowSeconds
        expectedKey = "(device_id, source_id, run_start + recordedAtOffsetSeconds, metric_name)"
        retryPolicy = "Retries must reuse sequence, deviceId, sourceId and recordedAtOffsetSeconds."
    }

    return [PSCustomObject]@{
        status = "PASS"
        preflight = $preflight
        fixture = $fixture
        schedule = $schedule
    }
}

function Write-CapacityFixtureSchedule {
    param(
        [Parameter(Mandatory = $true)] [object] $Prepared,
        [string] $OutputRoot = (Join-Path (Resolve-Path (Join-Path (Get-CapacityHarnessRoot) "..\..")).Path "artifacts\load-tests")
    )

    $evidence = Write-CapacityEvidence -Preflight $Prepared.preflight -OutputRoot $OutputRoot
    if ($Prepared.status -eq "BLOCKED") {
        return $evidence
    }

    $fixturePath = Join-Path $evidence.runDirectory "fixture-manifest.json"
    $schedulePath = Join-Path $evidence.runDirectory "schedule-manifest.json"
    $Prepared.fixture | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $fixturePath -Encoding UTF8
    $Prepared.schedule | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $schedulePath -Encoding UTF8

    $manifest = Get-Content -Raw -LiteralPath $evidence.manifestPath | ConvertFrom-Json
    $manifest | Add-Member -NotePropertyName fixtureManifestPath -NotePropertyValue "fixture-manifest.json" -Force
    $manifest | Add-Member -NotePropertyName scheduleManifestPath -NotePropertyValue "schedule-manifest.json" -Force
    $manifest | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $evidence.manifestPath -Encoding UTF8

    $evidence | Add-Member -NotePropertyName fixturePath -NotePropertyValue $fixturePath -Force
    $evidence | Add-Member -NotePropertyName schedulePath -NotePropertyValue $schedulePath -Force
    return $evidence
}

function ConvertTo-CapacityApiBaseUrl {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl
    )

    return $BaseUrl.Trim().TrimEnd("/")
}

function New-CapacityApiHeaders {
    param(
        [Parameter(Mandatory = $true)] [string] $UserId,
        [Parameter(Mandatory = $true)] [string] $Email,
        [string] $BearerToken = "",
        [string] $InternalServiceToken = ""
    )

    $headers = @{
        "Accept" = "application/json"
        "Content-Type" = "application/json"
        "X-Correlation-Id" = "capacity-provisioning-$UserId"
    }
    if ($BearerToken.Trim().Length -gt 0) {
        $headers["Authorization"] = "Bearer $BearerToken"
    } else {
        $headers["X-User-Id"] = $UserId
        $headers["X-User-Email"] = $Email
        $headers["X-User-Role"] = "USER"
    }
    if ($InternalServiceToken.Trim().Length -gt 0) {
        $headers["X-Internal-Service-Token"] = $InternalServiceToken
    }
    return $headers
}

function Invoke-CapacityApiJson {
    param(
        [Parameter(Mandatory = $true)] [ValidateSet("GET", "POST", "PATCH", "DELETE")] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [string] $Path,
        [hashtable] $Headers = @{},
        [AllowNull()] [object] $Body = $null,
        [int] $TimeoutSeconds = 30
    )

    $uri = "$(ConvertTo-CapacityApiBaseUrl -BaseUrl $BaseUrl)$Path"
    $arguments = @{
        Method = $Method
        Uri = $uri
        Headers = $Headers
        TimeoutSec = $TimeoutSeconds
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        $arguments.Body = ($Body | ConvertTo-Json -Depth 30)
    }
    return Invoke-RestMethod @arguments
}

function Read-CapacityJsonFile {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [AllowNull()] [object] $Default = $null
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $Default
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-CapacityJsonFile {
    param(
        [Parameter(Mandatory = $true)] [object] $Value,
        [Parameter(Mandatory = $true)] [string] $Path
    )

    $directory = Split-Path -Parent $Path
    if ($directory.Trim().Length -gt 0) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-CapacityClaimStatus {
    param(
        [Parameter(Mandatory = $true)] [string[]] $Verdicts
    )

    foreach ($status in @("FAIL", "BLOCKED", "INCONCLUSIVE", "NOT_RUN")) {
        if ($Verdicts -contains $status) {
            return $status
        }
    }
    return "PASS"
}

function Get-CapacityGeneratorValidity {
    param(
        [AllowNull()] [object] $Plan,
        [AllowNull()] [object] $Result,
        [AllowNull()] [object] $Thresholds = $null
    )

    $findings = New-Object System.Collections.Generic.List[object]
    if ($null -eq $Plan) {
        $findings.Add([PSCustomObject]@{
            status = "NOT_RUN"
            check = "generation-manifest"
            detail = "generation-manifest.json is absent."
        })
        return [PSCustomObject]@{
            status = "NOT_RUN"
            findings = @($findings.ToArray())
        }
    }
    if ($null -eq $Result) {
        $findings.Add([PSCustomObject]@{
            status = "NOT_RUN"
            check = "generation-result"
            detail = "generation-result.json is absent."
        })
        return [PSCustomObject]@{
            status = "NOT_RUN"
            findings = @($findings.ToArray())
        }
    }

    $scheduled = [int64] $Result.scheduledReadings
    $started = [int64] $Result.startedReadings
    $accepted = [int64] $Result.acceptedUniqueReadings
    $duplicates = [int64] $Result.duplicates
    $rejected = [int64] $Result.rejectedReadings
    $unknown = [int64] $Result.unknownOutcomes
    $timeouts = [int64] $Result.timeoutAfterAccept
    $dropped = [int64] $Result.droppedScheduledReadings
    $unexpected = @($Result.unexpectedOutcomeSequences).Count
    $expectedDropped = if ($null -ne $Thresholds -and $null -ne $Thresholds.droppedScheduledReadings) { [int64] $Thresholds.droppedScheduledReadings } else { 0 }

    if ($scheduled -ne [int64] $Plan.accounting.workerMaterializedReadings) {
        $findings.Add([PSCustomObject]@{
            status = "FAIL"
            check = "scheduled-readings"
            detail = "Generation result scheduled readings $scheduled do not match plan $($Plan.accounting.workerMaterializedReadings)."
        })
    }
    if ($Result.protocol -ne $Plan.protocol) {
        $findings.Add([PSCustomObject]@{
            status = "FAIL"
            check = "protocol"
            detail = "Generation result protocol '$($Result.protocol)' does not match plan '$($Plan.protocol)'."
        })
    }
    if ($unexpected -gt 0) {
        $findings.Add([PSCustomObject]@{
            status = "FAIL"
            check = "unexpected-outcomes"
            detail = "$unexpected outcome sequence(s) were not in the materialized schedule."
        })
    }
    if ($rejected -gt 0) {
        $findings.Add([PSCustomObject]@{
            status = "FAIL"
            check = "rejected-readings"
            detail = "$rejected reading(s) were rejected by the application."
        })
    }
    if ($dropped -gt $expectedDropped) {
        $findings.Add([PSCustomObject]@{
            status = "INCONCLUSIVE"
            check = "dropped-schedule"
            detail = "$dropped scheduled reading(s) were not started; threshold is $expectedDropped."
        })
    }
    if ($unknown -gt 0 -or $timeouts -gt 0) {
        $findings.Add([PSCustomObject]@{
            status = "INCONCLUSIVE"
            check = "ambiguous-outcomes"
            detail = "$unknown unknown outcome(s) and $timeouts timeout-after-accept outcome(s) require same-identity retry or reconciliation."
        })
    }
    if ($started -lt $scheduled -and $dropped -eq 0 -and $Result.status -ne "NOT_RUN") {
        $findings.Add([PSCustomObject]@{
            status = "INCONCLUSIVE"
            check = "started-coverage"
            detail = "Only $started of $scheduled scheduled readings have an observed outcome."
        })
    }
    if ($accepted + $duplicates + $rejected + $unknown -gt $scheduled + $timeouts) {
        $findings.Add([PSCustomObject]@{
            status = "FAIL"
            check = "outcome-accounting"
            detail = "Observed outcomes exceed scheduled readings after accounting for retry-only timeout identities."
        })
    }

    if ($Result.status -eq "NOT_RUN") {
        $findings.Add([PSCustomObject]@{
            status = "NOT_RUN"
            check = "execution"
            detail = "Generation was planned but not executed."
        })
    }

    if ($findings.Count -eq 0) {
        $findings.Add([PSCustomObject]@{
            status = "PASS"
            check = "generator-accounting"
            detail = "Observed generator accounting is internally consistent for the materialized plan."
        })
    }

    return [PSCustomObject]@{
        status = Get-CapacityClaimStatus -Verdicts @($findings | ForEach-Object { $_.status })
        findings = @($findings.ToArray())
    }
}

function Update-CapacityRunReport {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -eq $summary) {
        throw "summary.json not found in run directory: $RunDirectory"
    }

    $plan = Read-CapacityJsonFile -Path $paths.generationManifestPath -Default $null
    $generation = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
    $visibility = Read-CapacityJsonFile -Path $paths.visibilityPath -Default $null
    $reconciliation = Read-CapacityJsonFile -Path $paths.reconciliationPath -Default $null
    $runner = Read-CapacityJsonFile -Path $paths.runnerSummaryPath -Default $null
    $cancellation = Read-CapacityJsonFile -Path $paths.cancellationReportPath -Default $null
    $observability = Read-CapacityJsonFile -Path $paths.observabilityInventoryPath -Default $null
    $storageFixture = Read-CapacityJsonFile -Path $paths.storageFixturePath -Default $null
    $monthlyTrial = Read-CapacityJsonFile -Path $paths.monthlyTrialPath -Default $null
    $scalingTrial = Read-CapacityJsonFile -Path $paths.scalingTrialPath -Default $null
    $recoveryTrial = Read-CapacityJsonFile -Path $paths.recoveryTrialPath -Default $null

    $generatorValidity = Get-CapacityGeneratorValidity -Plan $plan -Result $generation -Thresholds $summary.thresholds
    Add-Member -InputObject $summary -NotePropertyName generatorValidity -NotePropertyValue $generatorValidity -Force

    if ($null -ne $generation) {
        $summary.verdicts.generation = $generation.status
    }
    if ($null -ne $visibility) {
        Add-Member -InputObject $summary.verdicts -NotePropertyName visibilityObservation -NotePropertyValue $visibility.status -Force
        Add-Member -InputObject $summary -NotePropertyName visibilityObservationPath -NotePropertyValue "visibility-observations.json" -Force
    }
    if ($null -ne $reconciliation) {
        $summary.verdicts.persistenceReconciliation = $reconciliation.status
        if ($reconciliation.status -eq "PASS" -and $null -ne $generation) {
            $measuredSeconds = if ($null -ne $generation.actualMeasuredSeconds -and [double] $generation.actualMeasuredSeconds -gt 0) { [double] $generation.actualMeasuredSeconds } else { [double] $summary.workload.measurementSeconds }
            $persistedValues = [int64] $reconciliation.committedMeasurements
            $metricCount = [int] $summary.workload.requiredNumericMetricCount
            $persistedReadings = if ($metricCount -gt 0) { [int64] ($persistedValues / $metricCount) } else { [int64] 0 }
            Add-Member -InputObject $summary -NotePropertyName measuredResult -NotePropertyValue ([PSCustomObject]@{
                actualMeasuredSeconds = $measuredSeconds
                persistedUniqueReadings = $persistedReadings
                persistedNumericValues = $persistedValues
                readingsPerSecond = [math]::Round($persistedReadings / $measuredSeconds, 6)
                numericValuesPerSecond = [math]::Round($persistedValues / $measuredSeconds, 6)
            }) -Force
            Add-Member -InputObject $summary -NotePropertyName dailyProjection -NotePropertyValue ([PSCustomObject]@{
                status = "EXTRAPOLATION"
                basis = "Reconciled persisted totals divided by actual measured seconds and multiplied by 86400."
                projectedReadings = [int64] [math]::Round(($persistedReadings / $measuredSeconds) * 86400)
                projectedNumericValues = [int64] [math]::Round(($persistedValues / $measuredSeconds) * 86400)
                dailyEnduranceVerdict = "NOT_RUN"
                monthlyPopulatedStorageVerdict = "NOT_RUN"
            }) -Force
        }
    }
    if ($null -ne $runner) {
        Add-Member -InputObject $summary -NotePropertyName runnerSummaryPath -NotePropertyValue "runner-summary.json" -Force
    }
    if ($null -ne $cancellation) {
        Add-Member -InputObject $summary -NotePropertyName cancellationReportPath -NotePropertyValue "cancellation-report.json" -Force
    }
    if ($null -ne $observability) {
        Add-Member -InputObject $summary -NotePropertyName observabilityInventoryPath -NotePropertyValue "observability-inventory.json" -Force
        Add-Member -InputObject $summary -NotePropertyName observabilityStatus -NotePropertyValue $observability.status -Force
    }
    if ($null -ne $storageFixture) {
        Add-Member -InputObject $summary -NotePropertyName storageFixturePath -NotePropertyValue "storage-fixture.json" -Force
        Add-Member -InputObject $summary -NotePropertyName storageFixtureStatus -NotePropertyValue $storageFixture.status -Force
    }
    if ($null -ne $monthlyTrial) {
        Add-Member -InputObject $summary -NotePropertyName monthlyTrialPath -NotePropertyValue "monthly-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName monthlyTrialStatus -NotePropertyValue $monthlyTrial.status -Force
        $summary.verdicts.monthlyVolume = if ($monthlyTrial.status -eq "BLOCKED") { "BLOCKED" } else { "INCONCLUSIVE" }
    }
    if ($null -ne $scalingTrial) {
        Add-Member -InputObject $summary -NotePropertyName scalingTrialPath -NotePropertyValue "scaling-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName scalingTrialStatus -NotePropertyValue $scalingTrial.status -Force
        $summary.verdicts.scaling = "INCONCLUSIVE"
    }
    if ($null -ne $recoveryTrial) {
        Add-Member -InputObject $summary -NotePropertyName recoveryTrialPath -NotePropertyValue "recovery-trial.json" -Force
        Add-Member -InputObject $summary -NotePropertyName recoveryTrialStatus -NotePropertyValue $recoveryTrial.status -Force
        $summary.verdicts.recovery = "INCONCLUSIVE"
    }

    $requiredClaimNames = @("profilePreflight", "generation", "visibilityObservation", "persistenceReconciliation")
    $requiredClaimVerdicts = @($requiredClaimNames | ForEach-Object {
        $property = $summary.verdicts.PSObject.Properties[$_]
        if ($null -eq $property) { "NOT_RUN" } else { [string] $property.Value }
    })
    Add-Member -InputObject $summary -NotePropertyName requiredClaimNames -NotePropertyValue $requiredClaimNames -Force
    Add-Member -InputObject $summary -NotePropertyName optionalClaimNames -NotePropertyValue @("monthlyVolume", "scaling", "recovery") -Force
    Add-Member -InputObject $summary -NotePropertyName overallEvidenceStatus -NotePropertyValue (Get-CapacityClaimStatus -Verdicts $requiredClaimVerdicts) -Force
    Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath

    $report = @(
        "# VoltCanary Capacity Evidence",
        "",
        "- Run ID: $($summary.runId)",
        "- Profile: $($summary.profile)",
        "- Phase: $($summary.phase)",
        "- Preflight status: $($summary.status)",
        "- Overall evidence status: $($summary.overallEvidenceStatus)",
        "- Devices: $($summary.workload.deviceCount)",
        "- Measurement seconds: $($summary.workload.measurementSeconds)",
        "- Scheduled readings: $($summary.workload.scheduledReadings)",
        "- Expected numeric values: $($summary.workload.expectedNumericValues)",
        "- Estimated REST requests at batch size $($summary.workload.batchSize): $($summary.workload.estimatedRequests)",
        "",
        "## Verdicts",
        "",
        "| Claim | Verdict |",
        "| --- | --- |"
    )
    foreach ($property in $summary.verdicts.PSObject.Properties) {
        $report += "| $($property.Name) | $($property.Value) |"
    }

    $report += @("", "## Generator Validity", "", "| Check | Verdict | Detail |", "| --- | --- | --- |")
    foreach ($finding in @($generatorValidity.findings)) {
        $detail = ([string] $finding.detail).Replace("|", "\|")
        $report += "| $($finding.check) | $($finding.status) | $detail |"
    }

    if ($null -ne $generation) {
        $report += @(
            "",
            "## Generation Accounting",
            "",
            "- Protocol: $($generation.protocol)",
            "- Scheduled readings: $($generation.scheduledReadings)",
            "- Started readings: $($generation.startedReadings)",
            "- Transport attempts: $($generation.transportAttempts)",
            "- Retries: $($generation.retries)",
            "- Accepted unique readings: $($generation.acceptedUniqueReadings)",
            "- Duplicates: $($generation.duplicates)",
            "- Rejected readings: $($generation.rejectedReadings)",
            "- Unknown outcomes: $($generation.unknownOutcomes)",
            "- Transport errors: $($generation.transportErrors)",
            "- Reconnect attempts: $($generation.reconnectAttempts)",
            "- MQTT QoS: $($generation.qos)",
            "- Connection count: $($generation.connectionCount)",
            "- Application persistence evidence: $($generation.applicationPersistenceEvidence)",
            "- Dropped scheduled readings: $($generation.droppedScheduledReadings)"
        )
    }

    if ($null -ne $visibility) {
        $report += @(
            "",
            "## Visibility Observation",
            "",
            "- Status: $($visibility.status)",
            "- Samples: $($visibility.sampleCount)",
            "- Observed: $($visibility.observedCount)",
            "- Timed out: $($visibility.timeoutCount)",
            "- p95 millis: $($visibility.p95Millis)",
            "- p99 millis: $($visibility.p99Millis)",
            "- Uncertainty: $($visibility.uncertainty)"
        )
    }

    if ($null -ne $reconciliation) {
        $report += @(
            "",
            "## Reconciliation",
            "",
            "- Status: $($reconciliation.status)",
            "- Expected measurements: $($reconciliation.expectedMeasurements)",
            "- Committed measurements: $($reconciliation.committedMeasurements)",
            "- Missing: $($reconciliation.missingCount)",
            "- Unexpected: $($reconciliation.unexpectedCount)",
            "- Wrong values: $($reconciliation.wrongValueCount)"
        )
    }
    if ($null -ne $summary.measuredResult) {
        $report += @(
            "",
            "## Measured Result",
            "",
            "- Actual measured seconds: $($summary.measuredResult.actualMeasuredSeconds)",
            "- Persisted unique readings: $($summary.measuredResult.persistedUniqueReadings)",
            "- Persisted numeric values: $($summary.measuredResult.persistedNumericValues)",
            "- Persisted readings/s: $($summary.measuredResult.readingsPerSecond)",
            "- Persisted numeric values/s: $($summary.measuredResult.numericValuesPerSecond)",
            "- Projected daily readings: $($summary.dailyProjection.projectedReadings) (EXTRAPOLATION)",
            "- Projected daily numeric values: $($summary.dailyProjection.projectedNumericValues) (EXTRAPOLATION)",
            "- 24-hour endurance: NOT_RUN",
            "- Populated monthly storage: NOT_RUN"
        )
    }

    if ($null -ne $observability) {
        $report += @("", "## Observability Inventory", "", "- Status: $($observability.status)", "- Expected meters: $(@($observability.expectedMeters).Count)", "- Scrape jobs: $(@($observability.scrapeJobs).Count)", "", "| Query | Purpose |", "| --- | --- |")
        foreach ($query in @($observability.queries)) {
            $purpose = ([string] $query.purpose).Replace("|", "\|")
            $report += "| $($query.name) | $purpose |"
        }
    }

    if ($null -ne $storageFixture) {
        $report += @(
            "",
            "## Storage Fixture",
            "",
            "- Status: $($storageFixture.status)",
            "- Mode: $($storageFixture.mode)",
            "- Scope: $($storageFixture.explicitScope)",
            "- Days: $($storageFixture.targetDistribution.days)",
            "- Expected readings: $($storageFixture.targetDistribution.totalReadings)",
            "- Expected numeric values: $($storageFixture.targetDistribution.totalNumericValues)",
            "- Disk estimate: $($storageFixture.diskEstimate.estimatedGiB) GiB ($($storageFixture.diskEstimate.status))",
            "- Storage claim status: $($storageFixture.storageClaimStatus)"
        )
    }

    if ($null -ne $monthlyTrial) {
        $report += @(
            "",
            "## Monthly Query/Write Trial",
            "",
            "- Status: $($monthlyTrial.status)",
            "- Claim status: $($monthlyTrial.monthlyClaimStatus)",
            "- Fixture mode: $($monthlyTrial.fixtureMode)",
            "- Loaded numeric values: $($monthlyTrial.dataIntegrity.loadedNumericValues)",
            "- Expected numeric values: $($monthlyTrial.dataIntegrity.expectedNumericValues)",
            "- Integrity status: $($monthlyTrial.dataIntegrity.status)",
            "",
            "| Phase | Repeats | Verdict |",
            "| --- | ---: | --- |"
        )
        foreach ($phase in @($monthlyTrial.queryPhases)) {
            $report += "| $($phase.name) | $($phase.repeats) | $($phase.verdict) |"
        }
    }

    if ($null -ne $scalingTrial) {
        $report += @(
            "",
            "## Scaling Trial",
            "",
            "- Status: $($scalingTrial.status)",
            "- Policy: $($scalingTrial.comparisonPolicy)",
            "- HPA status: $($scalingTrial.hpaTimeline.status)",
            "- HPA sample seconds: $($scalingTrial.hpaTimeline.sampleSeconds)",
            "",
            "| Replicas | Partitions | Verdict |",
            "| ---: | --- | --- |"
        )
        foreach ($fixedRun in @($scalingTrial.fixedReplicaRuns)) {
            $report += "| $($fixedRun.replicas) | $($fixedRun.partitions) | $($fixedRun.verdict) |"
        }
    }

    if ($null -ne $recoveryTrial) {
        $report += @(
            "",
            "## Recovery Trial",
            "",
            "- Status: $($recoveryTrial.status)",
            "- Scope: $($recoveryTrial.scope)",
            "- Acceptance policy: $($recoveryTrial.acceptancePolicy)",
            "",
            "| Target | Outage seconds | Drain seconds | Verdict |",
            "| --- | ---: | ---: | --- |"
        )
        foreach ($trial in @($recoveryTrial.trials)) {
            $report += "| $($trial.target) | $($trial.outageSeconds) | $($trial.drainSeconds) | $($trial.verdict) |"
        }
    }

    if ($summary.blockers.Count -gt 0) {
        $report += @("", "## Blockers", "")
        foreach ($blocker in $summary.blockers) {
            $report += "- $blocker"
        }
    }
    if ($summary.warnings.Count -gt 0) {
        $report += @("", "## Warnings", "")
        foreach ($warning in $summary.warnings) {
            $report += "- $warning"
        }
    }
    if ($null -ne $cancellation) {
        $report += @("", "## Cancellation", "", "- Status: $($cancellation.status)", "- Drain policy: $($cancellation.drainPolicy)", "- Restoration policy: $($cancellation.restorationPolicy)")
        foreach ($event in @($cancellation.guardEvents)) {
            $report += "- Guard $($event.guard): limit $($event.limit), scheduled $($event.scheduled), action $($event.action)"
        }
    }

    $report | Set-Content -LiteralPath $paths.reportPath -Encoding UTF8
    return [PSCustomObject]@{
        status = $summary.overallEvidenceStatus
        runDirectory = $RunDirectory
        summaryPath = $paths.summaryPath
        reportPath = $paths.reportPath
        generatorValidity = $generatorValidity
    }
}

function Get-CapacityRunPaths {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    return [PSCustomObject]@{
        runDirectory = $RunDirectory
        fixturePath = Join-Path $RunDirectory "fixture-manifest.json"
        schedulePath = Join-Path $RunDirectory "schedule-manifest.json"
        manifestPath = Join-Path $RunDirectory "manifest.json"
        summaryPath = Join-Path $RunDirectory "summary.json"
        generationManifestPath = Join-Path $RunDirectory "generation-manifest.json"
        generationResultPath = Join-Path $RunDirectory "generation-result.json"
        generationLogPath = Join-Path $RunDirectory "generation-output.log"
        k6SummaryPath = Join-Path $RunDirectory "k6-summary.json"
        visibilityPath = Join-Path $RunDirectory "visibility-observations.json"
        committedMeasurementsPath = Join-Path $RunDirectory "committed-measurements.csv"
        guardTimelinePath = Join-Path $RunDirectory "guard-timeline.json"
        reconciliationPath = Join-Path $RunDirectory "reconciliation.json"
        runnerSummaryPath = Join-Path $RunDirectory "runner-summary.json"
        cancellationReportPath = Join-Path $RunDirectory "cancellation-report.json"
        faultRestorationPath = Join-Path $RunDirectory "fault-restoration.json"
        environmentManifestPath = Join-Path $RunDirectory "environment-manifest.json"
        observabilityInventoryPath = Join-Path $RunDirectory "observability-inventory.json"
        storageFixturePath = Join-Path $RunDirectory "storage-fixture.json"
        monthlyTrialPath = Join-Path $RunDirectory "monthly-trial.json"
        scalingTrialPath = Join-Path $RunDirectory "scaling-trial.json"
        recoveryTrialPath = Join-Path $RunDirectory "recovery-trial.json"
        reportPath = Join-Path $RunDirectory "report.md"
    }
}

function Get-CapacityMetricValue {
    param(
        [Parameter(Mandatory = $true)] [int64] $Sequence,
        [Parameter(Mandatory = $true)] [string] $MetricName
    )

    switch ($MetricName) {
        "activePowerW" { return [math]::Round(350 + (($Sequence * 17) % 650) + (($Sequence % 10) / 10), 3) }
        "voltageV" { return [math]::Round(220 + (($Sequence * 7) % 20) + (($Sequence % 5) / 10), 3) }
        "currentA" { return [math]::Round(1 + (($Sequence * 11) % 900) / 100, 3) }
        "energyWh" { return [math]::Round(10000 + ($Sequence * 5), 3) }
        "rssi" { return [math]::Round(-70 + ($Sequence % 25), 3) }
        "temperatureC" { return [math]::Round(18 + (($Sequence * 3) % 550) / 10, 3) }
        "vibrationMmS" { return [math]::Round((($Sequence * 13) % 1200) / 100, 3) }
        "runtimeHours" { return [math]::Round(($Sequence * 5) / 60, 3) }
        "powerFactor" { return [math]::Round(0.7 + (($Sequence * 7) % 30) / 100, 3) }
        "frequencyHz" { return [math]::Round(49 + (($Sequence * 5) % 250) / 100, 3) }
        default {
            $hex = New-CapacityStableHex -Value "$Sequence`:$MetricName" -Length 8
            return [math]::Round(([Convert]::ToInt64($hex, 16) % 100000) / 100, 3)
        }
    }
}

function Resolve-CapacityProvisionedReadings {
    param(
        [Parameter(Mandatory = $true)] [object[]] $Readings,
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $provisioningPaths = Get-CapacityProvisioningPaths -RunDirectory $RunDirectory
    $state = Read-CapacityJsonFile -Path $provisioningPaths.statePath -Default $null
    if ($null -eq $state) {
        return @($Readings)
    }

    $deviceByIndex = @{}
    foreach ($device in @($state.devices)) {
        $deviceByIndex[[int] $device.deviceIndex] = $device
    }

    $sourceByIndex = @{}
    foreach ($source in @($state.dataSources)) {
        $sourceByIndex[[int] $source.deviceIndex] = $source
    }

    return @($Readings | ForEach-Object {
        $reading = $_ | Select-Object *
        $deviceIndex = [int] $reading.deviceIndex
        if ($deviceByIndex.ContainsKey($deviceIndex) -and $sourceByIndex.ContainsKey($deviceIndex)) {
            $device = $deviceByIndex[$deviceIndex]
            $source = $sourceByIndex[$deviceIndex]
            $reading.deviceId = $device.apiDeviceId
            $reading.sourceId = $source.externalId
            Add-Member -InputObject $reading -NotePropertyName fixtureDeviceId -NotePropertyValue $device.fixtureDeviceId -Force
            Add-Member -InputObject $reading -NotePropertyName fixtureSourceId -NotePropertyValue $source.fixtureSourceId -Force
            Add-Member -InputObject $reading -NotePropertyName apiDataSourceId -NotePropertyValue $source.apiDataSourceId -Force
            Add-Member -InputObject $reading -NotePropertyName provisionedIdentity -NotePropertyValue $true -Force
        } else {
            Add-Member -InputObject $reading -NotePropertyName provisionedIdentity -NotePropertyValue $false -Force
        }
        $reading
    })
}

function New-CapacityLogicalMeasurement {
    param(
        [Parameter(Mandatory = $true)] [object] $Reading,
        [Parameter(Mandatory = $true)] [string] $MetricName,
        [AllowNull()] [object] $NumericValue = $null
    )

    $value = if ($null -eq $NumericValue) {
        Get-CapacityMetricValue -Sequence ([int64] $Reading.sequence) -MetricName $MetricName
    } else {
        $NumericValue
    }

    return [PSCustomObject]@{
        sequence = [int64] $Reading.sequence
        deviceId = $Reading.deviceId
        sourceId = $Reading.sourceId
        recordedAtOffsetSeconds = [int64] $Reading.recordedAtOffsetSeconds
        metricName = $MetricName
        numericValue = [double] $value
        logicalKey = "$($Reading.deviceId)|$($Reading.sourceId)|$($Reading.recordedAtOffsetSeconds)|$MetricName"
    }
}

function New-CapacityGenerationPlan {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [ValidateSet("rest", "mqtt")] [string] $Protocol = "rest",
        [int] $WorkerIndex = 0,
        [int] $MaxReadings = 0,
        [string] $RestScriptPath = "scripts/load-tests/rest-ingest.k6.js",
        [string] $MqttGeneratorPath = "scripts/load-tests/mqtt-generator.ps1",
        [string] $PinnedK6Version = "0.52.0",
        [string] $PinnedMqttClientVersion = "PowerShell raw MQTT 3.1.1 TCP publisher",
        [string] $MqttClientImage = "native-powershell",
        [ValidateRange(0, 1)] [int] $MqttQos = 1,
        [ValidateRange(1, 100000)] [int] $MqttConnections = 1
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    if (-not (Test-Path -LiteralPath $paths.schedulePath)) {
        throw "schedule-manifest.json not found in run directory: $RunDirectory"
    }
    if (-not (Test-Path -LiteralPath $paths.fixturePath)) {
        throw "fixture-manifest.json not found in run directory: $RunDirectory"
    }

    $schedule = Read-CapacityJsonFile -Path $paths.schedulePath
    $fixture = Read-CapacityJsonFile -Path $paths.fixturePath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    $readings = @($schedule.materializedReadings | Where-Object { [int] $_.workerIndex -eq $WorkerIndex })
    if ($MaxReadings -gt 0) {
        $readings = @($readings | Select-Object -First $MaxReadings)
    }

    $provisioningStatePath = (Get-CapacityProvisioningPaths -RunDirectory $RunDirectory).statePath
    $hasProvisioningState = Test-Path -LiteralPath $provisioningStatePath
    $readings = Resolve-CapacityProvisionedReadings -Readings $readings -RunDirectory $RunDirectory
    $unresolvedIdentityCount = @($readings | Where-Object { $_.PSObject.Properties["provisionedIdentity"] -and $_.provisionedIdentity -eq $false }).Count

    $profileBatchSize = [int] $schedule.workload.batchSize
    $batchSize = if ($Protocol -eq "rest") { 1 } else { $profileBatchSize }
    $expectedRequests = if ($Protocol -eq "rest") { [int64] [math]::Ceiling(@($readings).Count / [double] $batchSize) } else { [int64] @($readings).Count }
    $tool = if ($Protocol -eq "rest") { "k6" } else { "mqtt" }
    $entrypoint = if ($Protocol -eq "rest") { $RestScriptPath } else { $MqttGeneratorPath }
    $versionPolicy = if ($Protocol -eq "rest") { $PinnedK6Version } else { $PinnedMqttClientVersion }
    $workerRange = @($schedule.workerRanges | Where-Object { [int] $_.workerIndex -eq $WorkerIndex } | Select-Object -First 1)
    $measurementSeconds = [int] $schedule.workload.measurementSeconds
    $warmupSeconds = [int] $schedule.workload.warmupSeconds
    $drainSeconds = [int] $schedule.workload.drainSeconds
    $rateTimeUnit = if ($measurementSeconds -gt 0) { "$($measurementSeconds)s" } else { "1s" }
    $duration = $rateTimeUnit
    $gracefulStop = if ($drainSeconds -gt 0) { "$($drainSeconds)s" } else { "30s" }
    $averageRequestsPerSecond = if ($measurementSeconds -gt 0) { [math]::Round($expectedRequests / [double] $measurementSeconds, 6) } else { 0 }
    $preAllocatedVus = [math]::Max(1, [math]::Min(200, [int] [math]::Ceiling([math]::Max(1, $averageRequestsPerSecond) * 2)))
    $maxVus = [math]::Max($preAllocatedVus, [math]::Min(1000, $preAllocatedVus * 4))
    $toolImage = if ($Protocol -eq "rest") { "grafana/k6:$PinnedK6Version" } else { $MqttClientImage }
    $warmupRequestCount = if ($Protocol -eq "rest" -and $warmupSeconds -gt 0 -and $measurementSeconds -gt 0) {
        [int64] [math]::Round($expectedRequests * ($warmupSeconds / [double] $measurementSeconds))
    } else {
        [int64] 0
    }
    $warmupReadings = @()
    if ($warmupRequestCount -gt 0 -and @($readings).Count -gt 0) {
        for ($warmupIndex = [int64] 0; $warmupIndex -lt $warmupRequestCount; $warmupIndex++) {
            $template = $readings[[int] ($warmupIndex % @($readings).Count)] | Select-Object *
            $scheduleOffsetSeconds = [int64] [math]::Floor($warmupIndex * $warmupSeconds / [double] $warmupRequestCount)
            $template.sequence = [int64] ($schedule.workload.scheduledReadings + ($WorkerIndex * $warmupRequestCount) + $warmupIndex)
            $template.recordedAtOffsetSeconds = [int64] ($scheduleOffsetSeconds - $warmupSeconds)
            Add-Member -InputObject $template -NotePropertyName scheduleOffsetSeconds -NotePropertyValue $scheduleOffsetSeconds -Force
            Add-Member -InputObject $template -NotePropertyName trafficPhase -NotePropertyValue "warmup" -Force
            $warmupReadings += $template
        }
    }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $schedule.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = if (@($readings).Count -eq 0 -or ($hasProvisioningState -and $unresolvedIdentityCount -gt 0)) { "BLOCKED" } else { "PLANNED" }
        profile = $schedule.profile
        phase = $schedule.phase
        protocol = $Protocol
        workerIndex = $WorkerIndex
        tool = $tool
        pinnedToolVersion = $versionPolicy
        toolImage = $toolImage
        entrypoint = $entrypoint
        scheduleManifestPath = "schedule-manifest.json"
        fixtureManifestPath = "fixture-manifest.json"
        sourceSecretPolicy = "Read source tokens from provisioning-secrets.local.json or runner environment; never persist them in generation manifests."
        identityResolution = [PSCustomObject]@{
            source = if ($hasProvisioningState) { "provisioning-state.json" } else { "fixture-manifest.json" }
            unresolvedReadings = $unresolvedIdentityCount
            restSourceScope = "REST generation sends one reading per request so each bearer token covers exactly one external source."
        }
        runEpoch = [PSCustomObject]@{
            iso = (Get-Date).ToUniversalTime().AddSeconds($warmupSeconds).ToString("o")
            policy = "The runner refreshes this epoch immediately before execution; it is the start of the measured window after warmup."
        }
        workerPartition = [PSCustomObject]@{
            workerCount = [int] $schedule.workerCount
            workerIndex = $WorkerIndex
            startSequenceInclusive = if ($workerRange.Count -gt 0) { [int64] $workerRange[0].startSequenceInclusive } else { $null }
            endSequenceExclusive = if ($workerRange.Count -gt 0) { [int64] $workerRange[0].endSequenceExclusive } else { $null }
            materializedStartSequence = if (@($readings).Count -gt 0) { [int64] $readings[0].sequence } else { $null }
            materializedEndSequenceExclusive = if (@($readings).Count -gt 0) { [int64] ($readings[-1].sequence + 1) } else { $null }
        }
        arrivalSchedule = [PSCustomObject]@{
            executor = if ($Protocol -eq "rest") { "constant-arrival-rate" } else { "external-mqtt-scheduler" }
            rate = $expectedRequests
            timeUnit = $rateTimeUnit
            duration = $duration
            gracefulStop = $gracefulStop
            averageRequestsPerSecond = $averageRequestsPerSecond
            preAllocatedVUs = $preAllocatedVus
            maxVUs = $maxVus
            maxRetries = 1
            droppedIterationThreshold = 0
            scheduleWaitPolicy = "Each k6 iteration may wait until the batch's first recordedAtOffsetSeconds; retries preserve the same payload identity."
        }
        warmupSchedule = [PSCustomObject]@{
            enabled = ($warmupRequestCount -gt 0)
            durationSeconds = $warmupSeconds
            expectedRequests = $warmupRequestCount
            rate = $warmupRequestCount
            timeUnit = if ($warmupSeconds -gt 0) { "$($warmupSeconds)s" } else { "1s" }
            accountingPolicy = "Warmup traffic uses distinct timestamps and is excluded from measured scheduled/accepted/persisted totals."
        }
        accounting = [PSCustomObject]@{
            scheduledReadings = [int64] $schedule.workload.scheduledReadings
            workerMaterializedReadings = [int64] @($readings).Count
            expectedRequests = $expectedRequests
            batchSize = $batchSize
            profileBatchSize = $profileBatchSize
            expectedNumericValues = [int64] (@($readings).Count * @($schedule.materializedReadings[0].metricNames).Count)
            totalExpectedNumericValues = if ($null -eq $summary) { $schedule.workload.expectedNumericValues } else { $summary.workload.expectedNumericValues }
        }
        mqtt = if ($Protocol -eq "mqtt") {
            [PSCustomObject]@{
                qos = $MqttQos
                requestedConnections = $MqttConnections
                clientVersion = $versionPolicy
                clientImage = $MqttClientImage
                reconnectAccounting = "Record reconnect attempts separately from application acceptance; publish acknowledgement remains transport-only."
                persistenceEvidence = "Committed persistence must be established by visibility observation and logical-key reconciliation."
            }
        } else {
            $null
        }
        readings = @($readings)
        warmupReadings = @($warmupReadings)
        command = if ($Protocol -eq "rest") {
            "docker run --rm -v <repo>:/workspace -w /workspace $toolImage run --env RUN_DIRECTORY=<run-dir> --env WORKER_INDEX=$WorkerIndex --summary-export <run-dir>/k6-summary.json $RestScriptPath"
        } else {
            "powershell -ExecutionPolicy Bypass -File $MqttGeneratorPath -RunDirectory <run-dir> -WorkerIndex $WorkerIndex -Qos $MqttQos -ConnectionCount $MqttConnections"
        }
        retryPolicy = "Retry ambiguous outcomes with the same sequence, deviceId, sourceId, timestamp and metric values."
        acceptancePolicy = if ($Protocol -eq "rest") {
            "Inspect HTTP status and per-item application response; 202 alone is not persistence evidence."
        } else {
            "MQTT publish acknowledgement is transport-only; application acceptance and persistence require reconciliation."
        }
    }
}

function Write-CapacityGenerationPlan {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [string] $RunDirectory = ""
    )

    if ($RunDirectory.Trim().Length -eq 0) {
        $RunDirectory = $Plan.runDirectory
    }
    if ($RunDirectory.Trim().Length -eq 0) {
        throw "RunDirectory is required."
    }

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Plan -Path $paths.generationManifestPath

    $manifest = Read-CapacityJsonFile -Path $paths.manifestPath -Default $null
    if ($null -ne $manifest) {
        $manifest | Add-Member -NotePropertyName generationManifestPath -NotePropertyValue "generation-manifest.json" -Force
        $manifest | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $paths.manifestPath -Encoding UTF8
    }

    return [PSCustomObject]@{
        status = $Plan.status
        runDirectory = $RunDirectory
        generationManifestPath = $paths.generationManifestPath
    }
}

function New-CapacityGenerationResult {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [int64[]] $AcceptedSequences = @(),
        [int64[]] $DuplicateSequences = @(),
        [int64[]] $RejectedSequences = @(),
        [int64[]] $UnknownSequences = @(),
        [int64[]] $TimeoutAfterAcceptSequences = @(),
        [int64[]] $TransportErrorSequences = @(),
        [int64[]] $RetriedSequences = @(),
        [int64] $ObservedTransportAttempts = 0,
        [int64] $DroppedScheduledReadings = 0,
        [int64] $ReconnectAttempts = 0,
        [int] $ConnectionCount = 0,
        [int] $Qos = -1,
        [string] $ClientVersion = ""
    )

    function New-SequenceSet {
        param([AllowEmptyCollection()] [object[]] $Values = @())
        $set = [System.Collections.Generic.HashSet[int64]]::new()
        foreach ($value in @($Values)) { $set.Add([int64] $value) | Out-Null }
        return ,$set
    }

    $readings = @($Plan.readings)
    $materializedSet = [System.Collections.Generic.HashSet[int64]]::new()
    foreach ($reading in $readings) { $materializedSet.Add([int64] $reading.sequence) | Out-Null }
    $acceptedSet = New-SequenceSet -Values $AcceptedSequences
    $duplicateSet = New-SequenceSet -Values $DuplicateSequences
    $rejectedSet = New-SequenceSet -Values $RejectedSequences
    $unknownSet = New-SequenceSet -Values $UnknownSequences
    $timeoutSet = New-SequenceSet -Values $TimeoutAfterAcceptSequences
    $transportErrorSet = New-SequenceSet -Values $TransportErrorSequences
    $retrySet = New-SequenceSet -Values $RetriedSequences
    $retrySet.UnionWith($unknownSet)
    $retrySet.UnionWith($timeoutSet)

    $outcomeSet = [System.Collections.Generic.HashSet[int64]]::new($acceptedSet)
    $outcomeSet.UnionWith($duplicateSet)
    $outcomeSet.UnionWith($rejectedSet)
    $outcomeSet.UnionWith($unknownSet)
    $outcomeSet.UnionWith($timeoutSet)
    $outcomeSet.UnionWith($transportErrorSet)
    $unexpectedSequences = [System.Collections.Generic.List[int64]]::new()
    foreach ($sequence in $outcomeSet) {
        if (-not $materializedSet.Contains($sequence)) { $unexpectedSequences.Add($sequence) }
    }

    $startedSet = [System.Collections.Generic.HashSet[int64]]::new($acceptedSet)
    $startedSet.UnionWith($duplicateSet)
    $startedSet.UnionWith($rejectedSet)
    $startedSet.UnionWith($unknownSet)
    $startedSet.UnionWith($transportErrorSet)
    $accepted = @($acceptedSet)
    $duplicates = @($duplicateSet)
    $rejected = @($rejectedSet)
    $unknown = @($unknownSet)
    $timeouts = @($timeoutSet)
    $transportErrors = @($transportErrorSet)
    $retrySequences = @($retrySet)
    $started = [int64] $startedSet.Count
    $attempts = if ($ObservedTransportAttempts -gt 0) { [int64] $ObservedTransportAttempts } else { [int64] ($started + $retrySet.Count + $transportErrorSet.Count) }
    $acceptedUnique = [int64] $accepted.Count
    $expectedNumericValues = [int64] ($acceptedUnique * @($Plan.readings[0].metricNames).Count)

    $status = "PASS"
    if ($unexpectedSequences.Count -gt 0 -or $rejected.Count -gt 0) {
        $status = "FAIL"
    } elseif ($unknown.Count -gt 0 -or $DroppedScheduledReadings -gt 0 -or $timeouts.Count -gt 0 -or $transportErrors.Count -gt 0) {
        $status = "INCONCLUSIVE"
    }

    $effectiveConnectionCount = if ($ConnectionCount -gt 0) {
        $ConnectionCount
    } elseif ($null -ne $Plan.mqtt -and $null -ne $Plan.mqtt.requestedConnections) {
        [int] $Plan.mqtt.requestedConnections
    } else {
        $null
    }
    $effectiveQos = if ($Qos -ge 0) {
        $Qos
    } elseif ($null -ne $Plan.mqtt -and $null -ne $Plan.mqtt.qos) {
        [int] $Plan.mqtt.qos
    } else {
        $null
    }
    $effectiveClientVersion = if ($ClientVersion.Trim().Length -gt 0) {
        $ClientVersion
    } elseif ($null -ne $Plan.mqtt -and $null -ne $Plan.mqtt.clientVersion) {
        [string] $Plan.mqtt.clientVersion
    } else {
        ""
    }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $Plan.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = $status
        protocol = $Plan.protocol
        workerIndex = $Plan.workerIndex
        scheduledReadings = [int64] $Plan.accounting.workerMaterializedReadings
        startedReadings = $started
        transportAttempts = $attempts
        retries = [int64] $retrySequences.Count
        acceptedUniqueReadings = $acceptedUnique
        duplicates = [int64] $duplicates.Count
        rejectedReadings = [int64] $rejected.Count
        unknownOutcomes = [int64] $unknown.Count
        timeoutAfterAccept = [int64] $timeouts.Count
        transportErrors = [int64] $transportErrors.Count
        reconnectAttempts = [int64] $ReconnectAttempts
        connectionCount = $effectiveConnectionCount
        qos = $effectiveQos
        clientVersion = $effectiveClientVersion
        applicationPersistenceEvidence = if ($Plan.protocol -eq "mqtt") { "REQUIRES_RECONCILIATION" } else { "HTTP_APPLICATION_RESPONSE_ONLY" }
        droppedScheduledReadings = [int64] $DroppedScheduledReadings
        expectedCommittedNumericValues = $expectedNumericValues
        unexpectedOutcomeSequences = @($unexpectedSequences.ToArray())
        retryIdentityPolicy = $Plan.retryPolicy
        acceptedSequences = @($accepted)
        duplicateSequences = @($duplicates)
        rejectedSequences = @($rejected)
        unknownSequences = @($unknown)
        timeoutAfterAcceptSequences = @($timeouts)
        transportErrorSequences = @($transportErrors)
        retriedSequences = @($retrySequences)
    }
}

function ConvertFrom-CapacityK6RestOutcomes {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $OutcomePath
    )

    if (-not (Test-Path -LiteralPath $OutcomePath)) {
        throw "k6 outcome log not found: $OutcomePath"
    }

    $accepted = New-Object System.Collections.Generic.List[int64]
    $duplicates = New-Object System.Collections.Generic.List[int64]
    $rejected = New-Object System.Collections.Generic.List[int64]
    $unknown = New-Object System.Collections.Generic.List[int64]
    $transportErrors = New-Object System.Collections.Generic.List[int64]
    $retried = New-Object System.Collections.Generic.List[int64]
    $observedSequences = New-Object System.Collections.Generic.HashSet[int64]
    $finalBySequence = @{}
    $attemptCount = [int64] 0
    $firstCompletedAtUnixMs = $null
    $lastCompletedAtUnixMs = $null

    foreach ($line in Get-Content -LiteralPath $OutcomePath) {
        $text = $line.ToString().Trim()
        if ($text.Length -eq 0) {
            continue
        }
        $messageMatch = [regex]::Match($text, 'msg="(?<message>(?:\\.|[^"])*)"')
        if ($messageMatch.Success) {
            try {
                $text = ('"' + $messageMatch.Groups["message"].Value + '"' | ConvertFrom-Json)
            } catch {
                continue
            }
        }
        $jsonStart = $text.IndexOf("{")
        if ($jsonStart -lt 0) {
            continue
        }
        $jsonEnd = $text.LastIndexOf("}")
        if ($jsonEnd -lt $jsonStart) { continue }
        try {
            $event = $text.Substring($jsonStart, $jsonEnd - $jsonStart + 1) | ConvertFrom-Json
        } catch {
            continue
        }
        if ($event.type -ne "capacity-rest-batch") {
            continue
        }

        $sequences = @($event.sequences | ForEach-Object { [int64] $_ })
        if ($sequences.Count -eq 0) {
            continue
        }
        $attempt = if ($null -ne $event.attempt) { [int] $event.attempt } else { 0 }
        if ($attempt -gt 0) {
            foreach ($sequence in $sequences) {
                $retried.Add($sequence)
            }
        }
        $attemptCount += $sequences.Count
        if ($null -ne $event.responseCompletedAtUnixMs) {
            $completedAt = [int64] $event.responseCompletedAtUnixMs
            if ($null -eq $firstCompletedAtUnixMs -or $completedAt -lt $firstCompletedAtUnixMs) { $firstCompletedAtUnixMs = $completedAt }
            if ($null -eq $lastCompletedAtUnixMs -or $completedAt -gt $lastCompletedAtUnixMs) { $lastCompletedAtUnixMs = $completedAt }
        }

        if ($event.status -eq "accepted") {
            $statuses = @($event.itemStatuses)
            for ($index = 0; $index -lt $sequences.Count; $index++) {
                $sequence = $sequences[$index]
                $status = if ($index -lt $statuses.Count) { [string] $statuses[$index] } else { "unknown" }
                $observedSequences.Add($sequence) | Out-Null
                $finalBySequence["$sequence"] = $status
            }
        } else {
            foreach ($sequence in $sequences) {
                $observedSequences.Add($sequence) | Out-Null
                $finalBySequence["$sequence"] = "unknown"
                if ($event.status -eq "transport-error") {
                    $transportErrors.Add($sequence)
                }
            }
        }
    }

    foreach ($entry in $finalBySequence.GetEnumerator()) {
        $sequence = [int64] $entry.Key
        switch ([string] $entry.Value) {
            "accepted" { $accepted.Add($sequence) }
            "duplicate" { $duplicates.Add($sequence) }
            "rejected" { $rejected.Add($sequence) }
            default { $unknown.Add($sequence) }
        }
    }

    $scheduled = @($Plan.readings | ForEach-Object { [int64] $_.sequence })
    $dropped = [int64] @($scheduled | Where-Object { -not $observedSequences.Contains($_) }).Count

    $result = New-CapacityGenerationResult `
        -Plan $Plan `
        -AcceptedSequences @($accepted.ToArray()) `
        -DuplicateSequences @($duplicates.ToArray()) `
        -RejectedSequences @($rejected.ToArray()) `
        -UnknownSequences @($unknown.ToArray()) `
        -TransportErrorSequences @($transportErrors.ToArray()) `
        -RetriedSequences @($retried.ToArray()) `
        -ObservedTransportAttempts $attemptCount `
        -DroppedScheduledReadings $dropped
    Add-Member -InputObject $result -NotePropertyName measurementFirstOutcomeUnixMs -NotePropertyValue $firstCompletedAtUnixMs -Force
    Add-Member -InputObject $result -NotePropertyName measurementLastOutcomeUnixMs -NotePropertyValue $lastCompletedAtUnixMs -Force
    Add-Member -InputObject $result -NotePropertyName actualMeasuredSeconds -NotePropertyValue $(if ($null -ne $firstCompletedAtUnixMs -and $null -ne $lastCompletedAtUnixMs) { [math]::Max(1, [math]::Round(($lastCompletedAtUnixMs - $firstCompletedAtUnixMs) / 1000.0, 3)) } else { $null }) -Force
    return $result
}

function Write-CapacityGenerationResult {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Result
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Result -Path $paths.generationResultPath

    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        $summary.verdicts.generation = $Result.status
        $summary | Add-Member -NotePropertyName generationResultPath -NotePropertyValue "generation-result.json" -Force
        $summary | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $paths.summaryPath -Encoding UTF8
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }

    return [PSCustomObject]@{
        status = $Result.status
        generationResultPath = $paths.generationResultPath
    }
}

function New-CapacityVisibilityObservation {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $Samples,
        [int] $PollingIntervalMillis = 250,
        [int] $TimeoutMillis = 30000,
        [double] $ThresholdMillis = 5000,
        [string] $LatencyReference = "accepted-response-to-first-committed-read"
    )

    $observed = @($Samples | Where-Object { $_.observed -eq $true -and $null -ne $_.latencyMillis })
    $latencies = @($observed | ForEach-Object { [double] $_.latencyMillis } | Sort-Object)
    $timeouts = @($Samples | Where-Object { $_.observed -ne $true })

    function Get-Percentile {
        param([double[]] $Values, [double] $Percentile)
        if ($Values.Count -eq 0) { return $null }
        $index = [math]::Ceiling(($Percentile / 100.0) * $Values.Count) - 1
        $index = [math]::Max(0, [math]::Min($index, $Values.Count - 1))
        return [math]::Round($Values[$index], 3)
    }

    $p95 = Get-Percentile -Values $latencies -Percentile 95
    $status = if (@($Samples).Count -eq 0) {
        "NOT_RUN"
    } elseif ($timeouts.Count -gt 0) {
        "INCONCLUSIVE"
    } elseif ($null -ne $p95 -and $ThresholdMillis -gt 0 -and $p95 -gt $ThresholdMillis) {
        "FAIL"
    } else {
        "PASS"
    }
    return [PSCustomObject]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = $status
        sampleCount = @($Samples).Count
        observedCount = $observed.Count
        timeoutCount = $timeouts.Count
        pollingIntervalMillis = $PollingIntervalMillis
        timeoutMillis = $TimeoutMillis
        p95Millis = $p95
        p99Millis = Get-Percentile -Values $latencies -Percentile 99
        thresholdMillis = $ThresholdMillis
        latencyReference = $LatencyReference
        uncertainty = "Polling latencies are upper bounds plus interval and network overhead; timed-out samples remain in the report."
        samples = @($Samples)
    }
}

function ConvertFrom-CapacityK6VisibilityOutcomes {
    param(
        [Parameter(Mandatory = $true)] [string] $OutcomePath,
        [double] $ThresholdMillis = 5000
    )

    if (-not (Test-Path -LiteralPath $OutcomePath)) {
        throw "k6 outcome log not found: $OutcomePath"
    }
    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($line in Get-Content -LiteralPath $OutcomePath) {
        $text = $line.ToString().Trim()
        $messageMatch = [regex]::Match($text, 'msg="(?<message>(?:\\.|[^"])*)"')
        if ($messageMatch.Success) {
            try {
                $text = ('"' + $messageMatch.Groups["message"].Value + '"' | ConvertFrom-Json)
            } catch {
                continue
            }
        }
        $jsonStart = $text.IndexOf("{")
        if ($jsonStart -lt 0) { continue }
        $jsonEnd = $text.LastIndexOf("}")
        if ($jsonEnd -lt $jsonStart) { continue }
        try { $event = $text.Substring($jsonStart, $jsonEnd - $jsonStart + 1) | ConvertFrom-Json } catch { continue }
        if ($event.type -ne "capacity-rest-visibility") { continue }
        $samples.Add([PSCustomObject]@{
            sequence = [int64] $event.sequence
            observed = [bool] $event.observed
            latencyMillis = if ($null -eq $event.latencyMillis) { $null } else { [double] $event.latencyMillis }
            acceptedAtUnixMs = [int64] $event.acceptedAtUnixMs
            pollingIntervalMillis = [int] $event.pollingIntervalMillis
            timeoutMillis = [int] $event.timeoutMillis
            error = $event.error
        })
    }
    $poll = if ($samples.Count -gt 0) { [int] $samples[0].pollingIntervalMillis } else { 250 }
    $timeout = if ($samples.Count -gt 0) { [int] $samples[0].timeoutMillis } else { 30000 }
    return New-CapacityVisibilityObservation -Samples @($samples.ToArray()) -PollingIntervalMillis $poll -TimeoutMillis $timeout -ThresholdMillis $ThresholdMillis -LatencyReference "accepted-response-to-first-telemetry-export-visibility"
}

function Write-CapacityVisibilityObservation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Observation
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Observation -Path $paths.visibilityPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        Add-Member -InputObject $summary.verdicts -NotePropertyName visibilityObservation -NotePropertyValue $Observation.status -Force
        Add-Member -InputObject $summary -NotePropertyName visibilityObservationPath -NotePropertyValue "visibility-observations.json" -Force
        Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Observation.status
        visibilityPath = $paths.visibilityPath
    }
}

function Invoke-CapacityVisibilityObservation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string] $TelemetryBaseUrl = "http://localhost:8084",
        [string] $InternalServiceToken = "dev-internal-token",
        [ValidateRange(0.001, 100)] [double] $SamplePercent = 1,
        [ValidateRange(1, 10000)] [int] $MaxSamples = 100,
        [int] $PollingIntervalMillis = 250,
        [int] $TimeoutMillis = 30000
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $plan = Read-CapacityJsonFile -Path $paths.generationManifestPath -Default $null
    $generation = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
    if ($null -eq $plan -or $null -eq $generation) {
        $observation = New-CapacityVisibilityObservation -Samples @() -PollingIntervalMillis $PollingIntervalMillis -TimeoutMillis $TimeoutMillis -ThresholdMillis 0 -LatencyReference "standalone-post-run-poll-start-to-visibility"
        return Write-CapacityVisibilityObservation -RunDirectory $RunDirectory -Observation $observation
    }

    $readingBySequence = @{}
    foreach ($reading in @($plan.readings)) {
        $readingBySequence[[int64] $reading.sequence] = $reading
    }
    $acceptedSequences = @($generation.acceptedSequences | ForEach-Object { [int64] $_ } | Where-Object { $readingBySequence.ContainsKey($_) } | Sort-Object -Unique)
    if ($acceptedSequences.Count -eq 0) {
        $observation = New-CapacityVisibilityObservation -Samples @() -PollingIntervalMillis $PollingIntervalMillis -TimeoutMillis $TimeoutMillis -ThresholdMillis 0 -LatencyReference "standalone-post-run-poll-start-to-visibility"
        return Write-CapacityVisibilityObservation -RunDirectory $RunDirectory -Observation $observation
    }

    $sampleCount = [int] [math]::Min($MaxSamples, [math]::Max(1, [math]::Ceiling($acceptedSequences.Count * ($SamplePercent / 100.0))))
    $sampleIndexes = New-Object System.Collections.Generic.HashSet[int]
    for ($i = 0; $i -lt $sampleCount; $i++) {
        $index = [int] [math]::Floor(($i * $acceptedSequences.Count) / [double] $sampleCount)
        $sampleIndexes.Add($index) | Out-Null
    }
    $sampleSequences = @($sampleIndexes | Sort-Object | ForEach-Object { $acceptedSequences[$_] })
    $parsedRunStart = [DateTimeOffset]::Parse([string] $plan.runEpoch.iso)
    $runStart = [DateTimeOffset]::FromUnixTimeMilliseconds($parsedRunStart.ToUnixTimeMilliseconds())
    $baseUrl = $TelemetryBaseUrl.TrimEnd("/")
    $headers = @{
        "X-Internal-Service-Token" = $InternalServiceToken
        "X-Correlation-Id" = "capacity-visibility-$($plan.runId)"
    }
    $samples = New-Object System.Collections.Generic.List[object]

    foreach ($sequence in $sampleSequences) {
        $reading = $readingBySequence[$sequence]
        $metricName = [string] @($reading.metricNames)[0]
        $expectedValue = [double] (Get-CapacityMetricValue -Sequence $sequence -MetricName $metricName)
        $recordedAt = $runStart.AddSeconds([int64] $reading.recordedAtOffsetSeconds)
        $timestamp = $recordedAt.UtcDateTime.ToString("o")
        $encodedMetric = [System.Uri]::EscapeDataString($metricName)
        $fromTimestamp = $recordedAt.AddMilliseconds(-2).UtcDateTime.ToString("o")
        $toTimestamp = $recordedAt.AddMilliseconds(2).UtcDateTime.ToString("o")
        $encodedFrom = [System.Uri]::EscapeDataString($fromTimestamp)
        $encodedTo = [System.Uri]::EscapeDataString($toTimestamp)
        $uri = "$baseUrl/telemetry/devices/$($reading.deviceId)/readings/export?metric=$encodedMetric&from=$encodedFrom&to=$encodedTo&limit=1"
        $pollStart = (Get-Date).ToUniversalTime()
        $deadline = $pollStart.AddMilliseconds($TimeoutMillis)
        $observed = $false
        $lastError = $null

        while ((Get-Date).ToUniversalTime() -lt $deadline -and -not $observed) {
            try {
                $response = Invoke-RestMethod -Method GET -Uri $uri -Headers $headers -TimeoutSec ([math]::Max(1, [math]::Ceiling($PollingIntervalMillis / 1000.0)))
                foreach ($point in @($response.points)) {
                    $pointTimestamp = [DateTimeOffset]::Parse([string] $point.timestamp)
                    $pointValue = [double] $point.value
                    if ([math]::Abs(($pointTimestamp - $recordedAt).TotalMilliseconds) -le 2 -and [math]::Abs($pointValue - $expectedValue) -le 0.000001) {
                        $observed = $true
                        break
                    }
                }
            } catch {
                $lastError = $_.Exception.Message
            }
            if (-not $observed) {
                Start-Sleep -Milliseconds $PollingIntervalMillis
            }
        }

        $latency = if ($observed) { [math]::Round(((Get-Date).ToUniversalTime() - $pollStart).TotalMilliseconds, 3) } else { $null }
        $samples.Add([PSCustomObject]@{
            sequence = $sequence
            deviceId = $reading.deviceId
            sourceId = $reading.sourceId
            metricName = $metricName
            recordedAt = $timestamp
            expectedValue = $expectedValue
            observed = $observed
            latencyMillis = $latency
            timeoutMillis = $TimeoutMillis
            pollingIntervalMillis = $PollingIntervalMillis
            error = $lastError
            latencyReference = "Measured from observer poll start; use reconciliation for full committed key/value integrity."
        })
    }

    $observation = New-CapacityVisibilityObservation -Samples @($samples.ToArray()) -PollingIntervalMillis $PollingIntervalMillis -TimeoutMillis $TimeoutMillis -ThresholdMillis 0 -LatencyReference "standalone-post-run-poll-start-to-visibility"
    return Write-CapacityVisibilityObservation -RunDirectory $RunDirectory -Observation $observation
}

function Test-CapacityLogicalReconciliation {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $ExpectedReadings,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $CommittedMeasurements
    )

    $expected = @{}
    $duplicateExpected = @{}
    foreach ($reading in @($ExpectedReadings)) {
        foreach ($metricName in @($reading.metricNames)) {
            $measurement = New-CapacityLogicalMeasurement -Reading $reading -MetricName $metricName
            if ($expected.ContainsKey($measurement.logicalKey)) {
                $duplicateExpected[$measurement.logicalKey] = if ($duplicateExpected.ContainsKey($measurement.logicalKey)) { $duplicateExpected[$measurement.logicalKey] + 1 } else { 2 }
                continue
            }
            $expected[$measurement.logicalKey] = $measurement
        }
    }

    $committed = @{}
    $duplicateCommitted = @{}
    $nonFiniteCommitted = @()
    foreach ($measurement in @($CommittedMeasurements)) {
        $key = if ($measurement.PSObject.Properties["logicalKey"]) {
            $measurement.logicalKey
        } else {
            "$($measurement.deviceId)|$($measurement.sourceId)|$($measurement.recordedAtOffsetSeconds)|$($measurement.metricName)"
        }
        $numericValue = [double] $measurement.numericValue
        if ([double]::IsNaN($numericValue) -or [double]::IsInfinity($numericValue)) {
            $nonFiniteCommitted += $key
        }
        if ($committed.ContainsKey($key)) {
            $duplicateCommitted[$key] = if ($duplicateCommitted.ContainsKey($key)) { $duplicateCommitted[$key] + 1 } else { 2 }
            continue
        }
        $committed[$key] = $measurement
    }

    $missing = @()
    $wrongValues = @()
    foreach ($key in $expected.Keys) {
        if (-not $committed.ContainsKey($key)) {
            $missing += $key
            continue
        }
        $expectedValue = [double] $expected[$key].numericValue
        $actualValue = [double] $committed[$key].numericValue
        if ([math]::Abs($expectedValue - $actualValue) -gt 0.000001) {
            $wrongValues += [PSCustomObject]@{
                logicalKey = $key
                expected = $expectedValue
                actual = $actualValue
            }
        }
    }

    $unexpected = @()
    foreach ($key in $committed.Keys) {
        if (-not $expected.ContainsKey($key)) {
            $unexpected += $key
        }
    }

    $status = if ($missing.Count -eq 0 -and $unexpected.Count -eq 0 -and $wrongValues.Count -eq 0 -and $duplicateExpected.Count -eq 0 -and $duplicateCommitted.Count -eq 0 -and $nonFiniteCommitted.Count -eq 0) { "PASS" } else { "FAIL" }
    return [PSCustomObject]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = $status
        expectedMeasurements = $expected.Count
        committedMeasurements = $committed.Count
        missingCount = $missing.Count
        unexpectedCount = $unexpected.Count
        wrongValueCount = $wrongValues.Count
        duplicateExpectedCount = $duplicateExpected.Count
        duplicateCommittedCount = $duplicateCommitted.Count
        nonFiniteCommittedCount = $nonFiniteCommitted.Count
        missingKeys = @($missing | Select-Object -First 100)
        unexpectedKeys = @($unexpected | Select-Object -First 100)
        wrongValues = @($wrongValues | Select-Object -First 100)
        duplicateExpectedKeys = @($duplicateExpected.Keys | Select-Object -First 100)
        duplicateCommittedKeys = @($duplicateCommitted.Keys | Select-Object -First 100)
        nonFiniteCommittedKeys = @($nonFiniteCommitted | Select-Object -First 100)
        partitioning = "Full runs must partition reconciliation by cohort/time window; this bounded result preserves logical key/value integrity."
    }
}

function Write-CapacityReconciliation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Reconciliation
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    Write-CapacityJsonFile -Value $Reconciliation -Path $paths.reconciliationPath
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    if ($null -ne $summary) {
        $summary.verdicts.persistenceReconciliation = $Reconciliation.status
        $summary | Add-Member -NotePropertyName reconciliationPath -NotePropertyValue "reconciliation.json" -Force
        $summary | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $paths.summaryPath -Encoding UTF8
        Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    }
    return [PSCustomObject]@{
        status = $Reconciliation.status
        reconciliationPath = $paths.reconciliationPath
    }
}

function ConvertFrom-CapacityCommittedMeasurementCsv {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [AllowNull()] [object] $Plan = $null,
        [Nullable[int64]] $MinOffsetSeconds = $null,
        [Nullable[int64]] $MaxOffsetSecondsExclusive = $null
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Committed measurement CSV not found: $Path"
    }
    $runStart = if ($null -ne $Plan -and $null -ne $Plan.runEpoch -and $null -ne $Plan.runEpoch.iso) {
        [DateTimeOffset]::Parse([string] $Plan.runEpoch.iso)
    } else {
        $null
    }

    return @(Import-Csv -LiteralPath $Path | ForEach-Object {
        $row = $_
        $offsetSeconds = if ($row.PSObject.Properties["recordedAtOffsetSeconds"] -and [string] $row.recordedAtOffsetSeconds -ne "") {
            [int64] $row.recordedAtOffsetSeconds
        } elseif ($row.PSObject.Properties["recordedAt"] -and $null -ne $runStart) {
            [int64] [math]::Round(([DateTimeOffset]::Parse([string] $row.recordedAt) - $runStart).TotalSeconds)
        } else {
            throw "Committed measurement CSV requires recordedAtOffsetSeconds or recordedAt with plan runEpoch."
        }
        $logicalKey = if ($row.PSObject.Properties["logicalKey"] -and [string] $row.logicalKey -ne "") {
            [string] $row.logicalKey
        } else {
            "$($row.deviceId)|$($row.sourceId)|$offsetSeconds|$($row.metricName)"
        }
        if (($null -eq $MinOffsetSeconds -or $offsetSeconds -ge [int64] $MinOffsetSeconds) -and ($null -eq $MaxOffsetSecondsExclusive -or $offsetSeconds -lt [int64] $MaxOffsetSecondsExclusive)) {
            [PSCustomObject]@{
                deviceId = [string] $row.deviceId
                sourceId = [string] $row.sourceId
                recordedAtOffsetSeconds = $offsetSeconds
                metricName = [string] $row.metricName
                numericValue = [double] $row.numericValue
                logicalKey = $logicalKey
            }
        }
    })
}

function Split-CapacityCommittedMeasurementCsv {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $OutputDirectory,
        [Parameter(Mandatory = $true)] [int64] $WindowSeconds,
        [Parameter(Mandatory = $true)] [int64] $MeasurementSeconds
    )

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $reader = [System.IO.StreamReader]::new($Path)
    $writers = @{}
    $paths = @{}
    try {
        $header = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($header) -or $header.ToLowerInvariant() -notlike "deviceid,sourceid,recordedatoffsetseconds,metricname,numericvalue*") {
            throw "Committed measurement CSV has an unsupported header for partitioned reconciliation."
        }
        for ($windowStart = [int64] 0; $windowStart -lt $MeasurementSeconds; $windowStart += $WindowSeconds) {
            $partitionPath = Join-Path $OutputDirectory "committed-$windowStart.csv"
            $writer = [System.IO.StreamWriter]::new($partitionPath, $false, [System.Text.UTF8Encoding]::new($false))
            $writer.WriteLine($header)
            $writers[$windowStart] = $writer
            $paths[$windowStart] = $partitionPath
        }
        while (($line = $reader.ReadLine()) -ne $null) {
            if ($line.Length -eq 0) { continue }
            $first = $line.IndexOf(',')
            $second = if ($first -ge 0) { $line.IndexOf(',', $first + 1) } else { -1 }
            $third = if ($second -ge 0) { $line.IndexOf(',', $second + 1) } else { -1 }
            if ($second -lt 0 -or $third -lt 0) { throw "Malformed committed measurement CSV row." }
            $offsetSeconds = [int64] $line.Substring($second + 1, $third - $second - 1)
            if ($offsetSeconds -lt 0 -or $offsetSeconds -ge $MeasurementSeconds) { continue }
            $windowStart = [int64] ([math]::Floor($offsetSeconds / $WindowSeconds) * $WindowSeconds)
            $writers[$windowStart].WriteLine($line)
        }
    } finally {
        $reader.Dispose()
        foreach ($writer in $writers.Values) { $writer.Dispose() }
    }
    return $paths
}

function Invoke-CapacityStreamingReconciliation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [string] $CommittedMeasurementsPath,
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [object] $Generation
    )

    $schedule = Read-CapacityJsonFile -Path (Get-CapacityRunPaths -RunDirectory $RunDirectory).schedulePath
    $state = Read-CapacityJsonFile -Path (Get-CapacityProvisioningPaths -RunDirectory $RunDirectory).statePath
    $deviceCount = [int64] $schedule.workload.deviceCount
    $intervalSeconds = [double] $schedule.workload.effectiveIntervalSeconds
    $phaseWindowSeconds = [int64] $schedule.phaseWindowSeconds
    $measurementSeconds = [int64] $schedule.workload.measurementSeconds
    $windowSeconds = [int64] [math]::Max(60, [double] $schedule.workload.intervalSeconds)
    $windowCount = [int] [math]::Ceiling($measurementSeconds / $windowSeconds)
    $metricNames = @($Plan.readings[0].metricNames)
    $metricSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($metricName in $metricNames) { $metricSet.Add([string] $metricName) | Out-Null }
    $accepted = [System.Collections.Generic.HashSet[int64]]::new()
    foreach ($sequence in @(@($Generation.acceptedSequences) + @($Generation.timeoutAfterAcceptSequences) + @($Generation.unknownSequences))) { $accepted.Add([int64] $sequence) | Out-Null }
    $sourceByName = @{}
    $sourceByIndex = @{}
    foreach ($source in @($state.dataSources)) { $sourceByName[[string] $source.externalId] = $source; $sourceByIndex[[int] $source.deviceIndex] = $source }
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $expectedByWindow = [int64[]]::new($windowCount)
    $committedByWindow = [int64[]]::new($windowCount)
    foreach ($sequence in $accepted) {
        $deviceIndex = [int64] ($sequence % $deviceCount)
        $cycleIndex = [int64] [math]::Floor($sequence / $deviceCount)
        $phaseOffset = [int64] [math]::Floor($deviceIndex * $phaseWindowSeconds / $deviceCount)
        $offset = [int64] [math]::Floor(($cycleIndex * $intervalSeconds) + $phaseOffset)
        $expectedByWindow[[int] [math]::Floor($offset / $windowSeconds)] += $metricNames.Count
    }

    $committedCount = [int64] 0; $unexpected = [int64] 0; $wrong = [int64] 0; $duplicates = [int64] 0; $nonFinite = [int64] 0
    $unexpectedKeys = [System.Collections.Generic.List[object]]::new(); $wrongValues = [System.Collections.Generic.List[object]]::new(); $duplicateKeys = [System.Collections.Generic.List[object]]::new(); $nonFiniteKeys = [System.Collections.Generic.List[object]]::new()
    $reader = [System.IO.StreamReader]::new($CommittedMeasurementsPath)
    try {
        $header = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($header) -or $header.ToLowerInvariant() -notlike "deviceid,sourceid,recordedatoffsetseconds,metricname,numericvalue*") { throw "Committed measurement CSV has an unsupported header." }
        while (($line = $reader.ReadLine()) -ne $null) {
            if ($line.Length -eq 0) { continue }
            $parts = $line.Split(',')
            if ($parts.Count -lt 5) { throw "Malformed committed measurement CSV row." }
            $deviceId = $parts[0]; $sourceId = $parts[1]; $offset = [int64] $parts[2]; $metricName = $parts[3]
            $committedCount += 1
            $source = $sourceByName[$sourceId]
            $validIdentity = $null -ne $source -and [string] $source.apiDeviceId -eq $deviceId
            $sequence = [int64] -1
            if ($validIdentity) {
                $deviceIndex = [int64] $source.deviceIndex
                $phaseOffset = [int64] [math]::Floor($deviceIndex * $phaseWindowSeconds / $deviceCount)
                $cycleIndex = [int64] [math]::Floor(($offset - $phaseOffset) / $intervalSeconds)
                $expectedOffset = [int64] [math]::Floor(($cycleIndex * $intervalSeconds) + $phaseOffset)
                if ($cycleIndex -ge 0 -and [math]::Abs($expectedOffset - $offset) -le 1) {
                    $sequence = ($cycleIndex * $deviceCount) + $deviceIndex
                    if ($expectedOffset -ge 0 -and $expectedOffset -lt $measurementSeconds) { $committedByWindow[[int] [math]::Floor($expectedOffset / $windowSeconds)] += 1 }
                }
            }
            $logicalKey = "$deviceId|$sourceId|$offset|$metricName"
            if ($sequence -lt 0 -or -not $accepted.Contains($sequence) -or -not $metricSet.Contains($metricName)) {
                $unexpected += 1; if ($unexpectedKeys.Count -lt 100) { $unexpectedKeys.Add($logicalKey) }; continue
            }
            $compactKey = "$sequence|$metricName"
            if (-not $seen.Add($compactKey)) { $duplicates += 1; if ($duplicateKeys.Count -lt 100) { $duplicateKeys.Add($logicalKey) }; continue }
            $numericValue = [double]::Parse($parts[4], [Globalization.CultureInfo]::InvariantCulture)
            if ([double]::IsNaN($numericValue) -or [double]::IsInfinity($numericValue)) { $nonFinite += 1; if ($nonFiniteKeys.Count -lt 100) { $nonFiniteKeys.Add($logicalKey) }; continue }
            $expectedValue = switch ($metricName) {
                "activePowerW" { [math]::Round(350 + (($sequence * 17) % 650) + (($sequence % 10) / 10), 3) }
                "voltageV" { [math]::Round(220 + (($sequence * 7) % 20) + (($sequence % 5) / 10), 3) }
                "currentA" { [math]::Round(1 + (($sequence * 11) % 900) / 100, 3) }
                "energyWh" { [math]::Round(10000 + ($sequence * 5), 3) }
                "rssi" { [math]::Round(-70 + ($sequence % 25), 3) }
                "temperatureC" { [math]::Round(18 + (($sequence * 3) % 550) / 10, 3) }
                "vibrationMmS" { [math]::Round((($sequence * 13) % 1200) / 100, 3) }
                "runtimeHours" { [math]::Round(($sequence * 5) / 60, 3) }
                "powerFactor" { [math]::Round(0.7 + (($sequence * 7) % 30) / 100, 3) }
                "frequencyHz" { [math]::Round(49 + (($sequence * 5) % 250) / 100, 3) }
            }
            if ([math]::Abs($numericValue - [double] $expectedValue) -gt 0.000001) { $wrong += 1; if ($wrongValues.Count -lt 100) { $wrongValues.Add([PSCustomObject]@{ logicalKey = $logicalKey; expected = $expectedValue; actual = $numericValue }) } }
        }
    } finally { $reader.Dispose() }
    $expectedCount = [int64] ($accepted.Count * $metricNames.Count)
    $missing = [int64] ($expectedCount - $seen.Count)
    $missingKeys = [System.Collections.Generic.List[object]]::new()
    if ($missing -gt 0) {
        foreach ($sequence in $accepted) {
            foreach ($metricName in $metricNames) {
                if ($missingKeys.Count -ge 100) { break }
                if (-not $seen.Contains("$sequence|$metricName")) {
                    $source = $sourceByIndex[[int] ($sequence % $deviceCount)]
                    $cycleIndex = [int64] [math]::Floor($sequence / $deviceCount); $phaseOffset = [int64] [math]::Floor(([int64] $source.deviceIndex) * $phaseWindowSeconds / $deviceCount); $offset = [int64] [math]::Floor(($cycleIndex * $intervalSeconds) + $phaseOffset)
                    $missingKeys.Add("$($source.apiDeviceId)|$($source.externalId)|$offset|$metricName")
                }
            }
            if ($missingKeys.Count -ge 100) { break }
        }
    }
    $windows = for ($index = 0; $index -lt $windowCount; $index++) { [PSCustomObject]@{ startOffsetSeconds = [int64] ($index * $windowSeconds); endOffsetSecondsExclusive = [int64] [math]::Min($measurementSeconds, ($index + 1) * $windowSeconds); status = if ($expectedByWindow[$index] -eq $committedByWindow[$index]) { "PASS" } else { "FAIL" }; expectedMeasurements = $expectedByWindow[$index]; committedMeasurements = $committedByWindow[$index] } }
    $failed = $missing + $unexpected + $wrong + $duplicates + $nonFinite
    return [PSCustomObject]@{
        schemaVersion = 1; generatedAt = (Get-Date).ToUniversalTime().ToString("o"); status = if ($failed -eq 0) { "PASS" } else { "FAIL" }
        expectedMeasurements = $expectedCount; committedMeasurements = $committedCount; missingCount = $missing; unexpectedCount = $unexpected; wrongValueCount = $wrong
        duplicateExpectedCount = 0; duplicateCommittedCount = $duplicates; nonFiniteCommittedCount = $nonFinite
        missingKeys = @($missingKeys); unexpectedKeys = @($unexpectedKeys); wrongValues = @($wrongValues); duplicateExpectedKeys = @(); duplicateCommittedKeys = @($duplicateKeys); nonFiniteCommittedKeys = @($nonFiniteKeys)
        partitioning = "Streamed once with compact sequence/metric keys and $windowSeconds-second counters."
        partitionWindows = @($windows)
    }
}

function Invoke-CapacityLogicalReconciliation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [string] $CommittedMeasurementsPath
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $plan = Read-CapacityJsonFile -Path $paths.generationManifestPath -Default $null
    $generation = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
    if ($null -eq $plan) {
        throw "generation-manifest.json not found in run directory: $RunDirectory"
    }
    if ($null -eq $generation) {
        throw "generation-result.json not found in run directory: $RunDirectory"
    }

    $readingBySequence = @{}
    foreach ($reading in @($plan.readings)) {
        $readingBySequence[[int64] $reading.sequence] = $reading
    }
    $expectedSequences = @(
        @($generation.acceptedSequences)
        @($generation.timeoutAfterAcceptSequences)
        @($generation.unknownSequences)
    ) | ForEach-Object { [int64] $_ } | Where-Object { $readingBySequence.ContainsKey($_) } | Sort-Object -Unique
    $expectedReadings = @($expectedSequences | ForEach-Object { $readingBySequence[$_] })
    $metricCount = if ($expectedReadings.Count -gt 0) { @($expectedReadings[0].metricNames).Count } else { 0 }
    $estimatedMeasurements = [int64] ($expectedReadings.Count * $metricCount)
    if ($estimatedMeasurements -le 200000) {
        $committed = @(ConvertFrom-CapacityCommittedMeasurementCsv -Path $CommittedMeasurementsPath -Plan $plan)
        $reconciliation = Test-CapacityLogicalReconciliation -ExpectedReadings $expectedReadings -CommittedMeasurements $committed
        Add-Member -InputObject $reconciliation -NotePropertyName partitionWindows -NotePropertyValue @() -Force
    } else {
        $reconciliation = Invoke-CapacityStreamingReconciliation -RunDirectory $RunDirectory -CommittedMeasurementsPath $CommittedMeasurementsPath -Plan $plan -Generation $generation
    }
    Add-Member -InputObject $reconciliation -NotePropertyName expectedSequenceCount -NotePropertyValue $expectedSequences.Count -Force
    Add-Member -InputObject $reconciliation -NotePropertyName ambiguousSequenceCount -NotePropertyValue @(@($generation.timeoutAfterAcceptSequences) + @($generation.unknownSequences) | Sort-Object -Unique).Count -Force
    Add-Member -InputObject $reconciliation -NotePropertyName committedMeasurementsSource -NotePropertyValue (Split-Path -Leaf $CommittedMeasurementsPath) -Force
    return Write-CapacityReconciliation -RunDirectory $RunDirectory -Reconciliation $reconciliation
}

function Export-CapacityCommittedMeasurements {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [int] $DrainSeconds = 300,
        [int] $PollSeconds = 5
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $plan = Read-CapacityJsonFile -Path $paths.generationManifestPath -Default $null
    $generation = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
    if ($null -eq $plan -or $null -eq $generation) { throw "Generation plan and result are required before committed export." }
    if ($null -eq (Get-Command "docker" -ErrorAction SilentlyContinue)) { throw "Docker is required for automatic local TimescaleDB export." }

    $dbUser = if ([string]::IsNullOrWhiteSpace($env:TIMESCALE_USER)) { "voltcanary" } else { $env:TIMESCALE_USER }
    $dbName = if ([string]::IsNullOrWhiteSpace($env:TIMESCALE_DB)) { "voltcanary_telemetry" } else { $env:TIMESCALE_DB }
    $dbPassword = if ([string]::IsNullOrWhiteSpace($env:TIMESCALE_PASSWORD)) { "change_me" } else { $env:TIMESCALE_PASSWORD }
    $parsedRunStart = [DateTimeOffset]::Parse([string] $plan.runEpoch.iso)
    $runStart = [DateTimeOffset]::FromUnixTimeMilliseconds($parsedRunStart.ToUnixTimeMilliseconds())
    $measurementSeconds = [int] ((Read-CapacityJsonFile -Path $paths.schedulePath).workload.measurementSeconds)
    $runEnd = $runStart.AddSeconds($measurementSeconds)
    $startSql = $runStart.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss.fff'Z'")
    $endSql = $runEnd.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss.fff'Z'")
    $expectedSequences = @(@($generation.acceptedSequences) + @($generation.timeoutAfterAcceptSequences) + @($generation.unknownSequences) | Sort-Object -Unique).Count
    $metricCount = @($plan.readings[0].metricNames).Count
    $expectedMeasurements = [int64] ($expectedSequences * $metricCount)
    $countSql = "select count(*) from telemetry.measurements where recorded_at >= timestamptz '$startSql' and recorded_at < timestamptz '$endSql' and source_id like 'capacity-source-%'"
    $deadline = (Get-Date).ToUniversalTime().AddSeconds([math]::Max(0, $DrainSeconds))
    $observedCount = [int64] 0
    $countError = $null
    do {
        $countOutput = & docker compose exec -T -e "PGPASSWORD=$dbPassword" timescaledb psql -U $dbUser -d $dbName -tA -v ON_ERROR_STOP=1 -c $countSql 2>&1
        if ($LASTEXITCODE -eq 0 -and [int64]::TryParse(($countOutput | Select-Object -Last 1).ToString().Trim(), [ref] $observedCount)) {
            $countError = $null
        } else {
            $countError = ($countOutput -join [Environment]::NewLine)
        }
        if ($observedCount -ge $expectedMeasurements -or (Get-Date).ToUniversalTime() -ge $deadline) { break }
        Start-Sleep -Seconds ([math]::Max(1, $PollSeconds))
    } while ($true)

    $safeRunId = ([string] $plan.runId) -replace '[^a-zA-Z0-9-]', '-'
    $remotePath = "/tmp/voltcanary-capacity-$safeRunId.csv"
    $copySql = "\copy (select device_id as deviceid, source_id as sourceid, round(extract(epoch from (recorded_at - timestamptz '$startSql')))::bigint as recordedatoffsetseconds, metric_name as metricname, metric_value as numericvalue, received_at as receivedat from telemetry.measurements where recorded_at >= timestamptz '$startSql' and recorded_at < timestamptz '$endSql' and source_id like 'capacity-source-%' order by recorded_at, device_id, source_id, metric_name) TO '$remotePath' WITH (FORMAT CSV, HEADER true)"
    $exportOutput = & docker compose exec -T -e "PGPASSWORD=$dbPassword" timescaledb psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $copySql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Committed export failed: $($exportOutput -join ' ')" }
    & docker compose cp "timescaledb:$remotePath" $paths.committedMeasurementsPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to copy committed export from TimescaleDB." }
    & docker compose exec -T timescaledb rm -f $remotePath | Out-Null

    return [PSCustomObject]@{
        status = if ($null -ne $countError) { "INCONCLUSIVE" } elseif ($observedCount -lt $expectedMeasurements) { "INCONCLUSIVE" } else { "PASS" }
        expectedMeasurements = $expectedMeasurements
        observedMeasurementsAtDrain = $observedCount
        drainSeconds = $DrainSeconds
        countError = $countError
        committedMeasurementsPath = $paths.committedMeasurementsPath
    }
}

function Invoke-CapacityAutomaticReconciliation {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [int] $DrainSeconds = 300
    )
    $export = Export-CapacityCommittedMeasurements -RunDirectory $RunDirectory -DrainSeconds $DrainSeconds
    $reconciliation = Invoke-CapacityLogicalReconciliation -RunDirectory $RunDirectory -CommittedMeasurementsPath $export.committedMeasurementsPath
    return [PSCustomObject]@{
        status = if ($export.status -eq "PASS" -and $reconciliation.status -eq "PASS") { "PASS" } elseif ($reconciliation.status -eq "FAIL") { "FAIL" } else { "INCONCLUSIVE" }
        export = $export
        reconciliation = $reconciliation
    }
}

function Get-CapacityRunDiskSnapshot {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory
    )

    $resolved = Resolve-Path -LiteralPath $RunDirectory
    $driveName = ([System.IO.Path]::GetPathRoot($resolved.Path)).TrimEnd("\").TrimEnd(":")
    $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
    if ($null -eq $drive) {
        return [PSCustomObject]@{
            status = "UNAVAILABLE"
            drive = $driveName
            freePercent = $null
            freeBytes = $null
            usedBytes = $null
        }
    }

    $total = [double] ($drive.Free + $drive.Used)
    $freePercent = if ($total -le 0) { $null } else { [math]::Round(([double] $drive.Free / $total) * 100, 3) }
    $dockerDataPath = $null
    $dockerSettingsPath = Join-Path $env:APPDATA "Docker\settings.json"
    if (Test-Path -LiteralPath $dockerSettingsPath) {
        try {
            $dockerSettings = Get-Content -LiteralPath $dockerSettingsPath -Raw | ConvertFrom-Json
            if (-not [string]::IsNullOrWhiteSpace([string] $dockerSettings.customWslDistroDir)) {
                $dockerDataPath = [string] $dockerSettings.customWslDistroDir
            } elseif (-not [string]::IsNullOrWhiteSpace([string] $dockerSettings.dataFolder)) {
                $dockerDataPath = [string] $dockerSettings.dataFolder
            }
        } catch {
            $dockerDataPath = $null
        }
    }
    $dockerDisk = $null
    if (-not [string]::IsNullOrWhiteSpace($dockerDataPath)) {
        $dockerRoot = [System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($dockerDataPath))
        $dockerDriveName = $dockerRoot.TrimEnd("\").TrimEnd(":")
        $dockerDrive = Get-PSDrive -Name $dockerDriveName -ErrorAction SilentlyContinue
        if ($null -ne $dockerDrive) {
            $dockerTotal = [double] ($dockerDrive.Free + $dockerDrive.Used)
            $dockerDisk = [PSCustomObject]@{
                status = "OBSERVED"
                drive = $dockerDriveName
                path = $dockerDataPath
                freePercent = if ($dockerTotal -le 0) { $null } else { [math]::Round(([double] $dockerDrive.Free / $dockerTotal) * 100, 3) }
                freeBytes = [int64] $dockerDrive.Free
                usedBytes = [int64] $dockerDrive.Used
            }
        }
    }
    return [PSCustomObject]@{
        status = "OBSERVED"
        drive = $driveName
        freePercent = $freePercent
        freeBytes = [int64] $drive.Free
        usedBytes = [int64] $drive.Used
        dockerData = $dockerDisk
    }
}

function Test-CapacityRunnerGuards {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [int] $MaxDurationSeconds = 0,
        [double] $MinFreeDiskPercent = 0,
        [double] $ObservedErrorRatePercent = 0,
        [double] $MaxErrorRatePercent = 5,
        [int] $ObservedLagGrowthSeconds = 0,
        [int] $MaxLagGrowthSeconds = 300
    )

    $events = New-Object System.Collections.Generic.List[object]
    $disk = Get-CapacityRunDiskSnapshot -RunDirectory $RunDirectory
    if ($MinFreeDiskPercent -gt 0 -and $null -ne $disk.freePercent -and [double] $disk.freePercent -lt $MinFreeDiskPercent) {
        $events.Add([PSCustomObject]@{
            guard = "min-free-disk-percent"
            limit = $MinFreeDiskPercent
            observed = [double] $disk.freePercent
            action = "generation-stopped"
        })
    }
    if ($MinFreeDiskPercent -gt 0 -and $null -ne $disk.dockerData -and $null -ne $disk.dockerData.freePercent -and [double] $disk.dockerData.freePercent -lt $MinFreeDiskPercent) {
        $events.Add([PSCustomObject]@{
            guard = "docker-data-min-free-disk-percent"
            limit = $MinFreeDiskPercent
            observed = [double] $disk.dockerData.freePercent
            drive = $disk.dockerData.drive
            action = "generation-stopped"
        })
    }
    if ($MaxDurationSeconds -gt 0) {
        $events.Add([PSCustomObject]@{
            guard = "max-duration-seconds"
            limit = $MaxDurationSeconds
            observed = "CONFIGURED"
            action = "deadline-armed"
        })
    }
    if ($MaxErrorRatePercent -ge 0 -and $ObservedErrorRatePercent -gt $MaxErrorRatePercent) {
        $events.Add([PSCustomObject]@{
            guard = "max-error-rate-percent"
            limit = $MaxErrorRatePercent
            observed = $ObservedErrorRatePercent
            action = "generation-stopped"
        })
    }
    if ($MaxLagGrowthSeconds -ge 0 -and $ObservedLagGrowthSeconds -gt $MaxLagGrowthSeconds) {
        $events.Add([PSCustomObject]@{
            guard = "max-lag-growth-seconds"
            limit = $MaxLagGrowthSeconds
            observed = $ObservedLagGrowthSeconds
            action = "generation-stopped"
        })
    }

    return [PSCustomObject]@{
        status = if (($events | Where-Object { $_.action -eq "generation-stopped" }).Count -gt 0) { "STOP" } else { "PASS" }
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        disk = $disk
        events = @($events.ToArray())
    }
}

function ConvertTo-CapacityContainerPath {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [string] $RepoRoot = (Get-CapacityRepoRoot)
    )

    $repoFullPath = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd("\", "/")
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($repoFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path '$Path' is outside repository root '$repoFullPath' and cannot be mounted into the k6 container."
    }

    $relative = $fullPath.Substring($repoFullPath.Length).TrimStart("\", "/")
    if ($relative.Length -eq 0) {
        return "/workspace"
    }
    return "/workspace/" + ($relative -replace "\\", "/")
}

function Invoke-CapacityLoggedProcess {
    param(
        [Parameter(Mandatory = $true)] [string] $FileName,
        [string[]] $ArgumentList = @(),
        [string] $WorkingDirectory = (Get-CapacityRepoRoot),
        [Parameter(Mandatory = $true)] [string] $LogPath,
        [hashtable] $Environment = @{},
        [scriptblock] $GuardProbe = $null,
        [int] $GuardPollSeconds = 5
    )

    $parent = Split-Path -Parent $LogPath
    if ($parent.Trim().Length -gt 0) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    try {
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $FileName
        $startInfo.WorkingDirectory = $WorkingDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        foreach ($argument in $ArgumentList) { $startInfo.ArgumentList.Add([string] $argument) }
        foreach ($key in $Environment.Keys) { $startInfo.Environment[$key] = [string] $Environment[$key] }
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        if (-not $process.Start()) { throw "Failed to start $FileName." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $guardEvents = New-Object System.Collections.Generic.List[object]
        while (-not $process.HasExited) {
            if ($null -ne $GuardProbe) {
                $probe = & $GuardProbe
                foreach ($event in @($probe.events)) { $guardEvents.Add($event) }
                if ($probe.status -eq "STOP") {
                    try { $process.Kill($true) } catch { }
                    break
                }
            }
            Start-Sleep -Seconds ([math]::Max(1, $GuardPollSeconds))
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        ($stdout + $(if ([string]::IsNullOrWhiteSpace($stderr)) { "" } else { [Environment]::NewLine + $stderr })) | Set-Content -LiteralPath $LogPath -Encoding UTF8
        $exitCode = $process.ExitCode
        return [PSCustomObject]@{
            status = if ($guardEvents.Count -gt 0) { "STOPPED_BY_GUARD" } elseif ($exitCode -eq 0) { "PASS" } else { "FAIL" }
            exitCode = $exitCode
            logPath = $LogPath
            guardEvents = @($guardEvents.ToArray())
        }
    } catch {
        $_.Exception.Message | Set-Content -LiteralPath $LogPath -Encoding UTF8
        return [PSCustomObject]@{
            status = "FAIL"
            exitCode = $null
            logPath = $LogPath
            error = $_.Exception.Message
        }
    }
}

function New-CapacityLiveGuardProbe {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [double] $MinFreeDiskPercent = 20,
        [double] $MaxErrorRatePercent = 5,
        [int] $MaxLagGrowthSeconds = 300,
        [string] $PrometheusBaseUrl = "http://localhost:9090"
    )
    $diskSnapshotCommand = ${function:Get-CapacityRunDiskSnapshot}
    $lastLag = $null
    $lagGrowthStartedAt = $null
    return {
        $snapshot = & $diskSnapshotCommand -RunDirectory $RunDirectory
        $events = New-Object System.Collections.Generic.List[object]
        foreach ($candidate in @(
            [PSCustomObject]@{ name = "artifact-disk"; state = $snapshot },
            [PSCustomObject]@{ name = "docker-data-disk"; state = $snapshot.dockerData }
        )) {
            if ($null -ne $candidate.state -and $null -ne $candidate.state.freePercent -and [double] $candidate.state.freePercent -lt $MinFreeDiskPercent) {
                $events.Add([PSCustomObject]@{
                    guard = "$($candidate.name)-min-free-percent"
                    limit = $MinFreeDiskPercent
                    observed = [double] $candidate.state.freePercent
                    drive = $candidate.state.drive
                    action = "generation-stopped"
                    observedAt = (Get-Date).ToUniversalTime().ToString("o")
                })
            }
        }
        $queries = @{
            errorRate = '100 * sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) / clamp_min(sum(rate(http_server_requests_seconds_count[1m])), 1)'
            consumerLag = 'sum(clamp_min(redpanda_kafka_consumer_group_latest_offset - redpanda_kafka_consumer_group_committed_offset, 0))'
        }
        $observations = @{}
        foreach ($name in $queries.Keys) {
            try {
                $uri = "$($PrometheusBaseUrl.TrimEnd('/'))/api/v1/query?query=$([System.Uri]::EscapeDataString($queries[$name]))"
                $response = Invoke-RestMethod -Method GET -Uri $uri -TimeoutSec 3
                if ($response.status -eq "success" -and @($response.data.result).Count -gt 0) {
                    $observations[$name] = [double] @($response.data.result)[0].value[1]
                }
            } catch { }
        }
        if ($observations.ContainsKey("errorRate") -and [double] $observations.errorRate -gt $MaxErrorRatePercent) {
            $events.Add([PSCustomObject]@{ guard = "prometheus-error-rate-percent"; limit = $MaxErrorRatePercent; observed = [double] $observations.errorRate; action = "generation-stopped"; observedAt = (Get-Date).ToUniversalTime().ToString("o") })
        }
        if ($observations.ContainsKey("consumerLag")) {
            $now = (Get-Date).ToUniversalTime()
            $lag = [double] $observations.consumerLag
            if ($null -ne $lastLag -and $lag -gt [double] $lastLag) {
                if ($null -eq $lagGrowthStartedAt) { $lagGrowthStartedAt = $now }
                if (($now - $lagGrowthStartedAt).TotalSeconds -gt $MaxLagGrowthSeconds) {
                    $events.Add([PSCustomObject]@{ guard = "prometheus-consumer-lag-growth"; limitSeconds = $MaxLagGrowthSeconds; observed = $lag; action = "generation-stopped"; observedAt = $now.ToString("o") })
                }
            } else {
                $lagGrowthStartedAt = $null
            }
            $lastLag = $lag
        }
        [PSCustomObject]@{
            status = if ($events.Count -gt 0) { "STOP" } else { "PASS" }
            events = @($events.ToArray())
            disk = $snapshot
            observations = $observations
        }
    }.GetNewClosure()
}

function New-CapacityK6Environment {
    param(
        [Parameter(Mandatory = $true)] [object] $Plan,
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [string] $SummaryPath,
        [Parameter(Mandatory = $true)] [string] $SecretPath,
        [int] $MaxDurationSeconds = 0,
        [double] $MaxErrorRatePercent = 5,
        [switch] $ContainerPaths
    )

    $runDirectoryValue = if ($ContainerPaths) { ConvertTo-CapacityContainerPath -Path $RunDirectory } else { $RunDirectory }
    $summaryPathValue = if ($ContainerPaths) { ConvertTo-CapacityContainerPath -Path $SummaryPath } else { $SummaryPath }
    $secretPathValue = if ($ContainerPaths) { ConvertTo-CapacityContainerPath -Path $SecretPath } else { $SecretPath }
    $duration = [string] $Plan.arrivalSchedule.duration

    $ingestionBaseUrl = [Environment]::GetEnvironmentVariable("INGESTION_BASE_URL", "Process")
    if ([string]::IsNullOrWhiteSpace($ingestionBaseUrl)) {
        $ingestionBaseUrl = if ($ContainerPaths) { "http://host.docker.internal:8083" } else { "http://localhost:8083" }
    }

    return @{
        RUN_DIRECTORY = $runDirectoryValue
        SOURCE_TOKENS_PATH = $secretPathValue
        RUN_START = [string] $Plan.runEpoch.iso
        SCHEDULE_WALL_CLOCK_START_MS = ([DateTimeOffset]::Parse([string] $Plan.runEpoch.iso).AddSeconds(-[int] $Plan.warmupSchedule.durationSeconds)).ToUnixTimeMilliseconds().ToString()
        CAPACITY_K6_LOG_OUTCOMES = "1"
        INGESTION_BASE_URL = $ingestionBaseUrl
        TELEMETRY_BASE_URL = if ($ContainerPaths) { "http://host.docker.internal:8084" } else { "http://localhost:8084" }
        INTERNAL_SERVICE_TOKEN = if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("INTERNAL_SERVICE_TOKEN", "Process"))) { "dev-internal-token" } else { [Environment]::GetEnvironmentVariable("INTERNAL_SERVICE_TOKEN", "Process") }
        VISIBILITY_SAMPLE_PERCENT = "1"
        VISIBILITY_MAX_SAMPLES = "100"
        VISIBILITY_POLL_MILLIS = "250"
        VISIBILITY_TIMEOUT_MILLIS = "30000"
        CAPACITY_BATCH_RATE = [string] [math]::Ceiling([double] $Plan.accounting.workerMaterializedReadings / [double] $Plan.accounting.batchSize)
        CAPACITY_TIME_UNIT = [string] $Plan.arrivalSchedule.timeUnit
        CAPACITY_DURATION = $duration
        CAPACITY_PRE_ALLOCATED_VUS = [string] $Plan.arrivalSchedule.preAllocatedVUs
        CAPACITY_MAX_VUS = [string] $Plan.arrivalSchedule.maxVUs
        CAPACITY_GRACEFUL_STOP = [string] $Plan.arrivalSchedule.gracefulStop
        MAX_DROPPED_ITERATIONS = [string] $Plan.arrivalSchedule.droppedIterationThreshold
        MAX_ERROR_RATE = [string] ($MaxErrorRatePercent / 100.0)
        CAPACITY_SUMMARY_EXPORT = $summaryPathValue
    }
}

function Invoke-CapacityRestGeneration {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Plan,
        [ValidateSet("auto", "local", "docker")] [string] $K6Mode = "auto",
        [int] $MaxDurationSeconds = 0,
        [double] $MinFreeDiskPercent = 20,
        [double] $MaxErrorRatePercent = 5,
        [int] $MaxLagGrowthSeconds = 300
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $repoRoot = Get-CapacityRepoRoot
    $secretPath = (Get-CapacityProvisioningPaths -RunDirectory $RunDirectory).secretPath
    if (-not (Test-Path -LiteralPath $secretPath)) {
        throw "Provisioned source token file not found: $secretPath. Run provision-fixtures.ps1 with -RotateSourceTokens first."
    }
    $selectedMode = $K6Mode
    if ($selectedMode -eq "auto") {
        $selectedMode = if ($null -ne (Get-Command "k6" -ErrorAction SilentlyContinue)) { "local" } else { "docker" }
    }

    $warmupSeconds = [int] $Plan.warmupSchedule.durationSeconds
    $measurementStart = [DateTimeOffset]::UtcNow.AddSeconds($warmupSeconds)
    $Plan.runEpoch.iso = $measurementStart.ToString("o")
    Write-CapacityGenerationPlan -Plan $Plan -RunDirectory $RunDirectory | Out-Null
    $liveGuardProbe = New-CapacityLiveGuardProbe -RunDirectory $RunDirectory -MinFreeDiskPercent $MinFreeDiskPercent -MaxErrorRatePercent $MaxErrorRatePercent -MaxLagGrowthSeconds $MaxLagGrowthSeconds

    if ($selectedMode -eq "local") {
        if ($null -eq (Get-Command "k6" -ErrorAction SilentlyContinue)) {
            throw "k6 is not available on PATH. Use -K6Mode docker or install k6."
        }
        $environment = New-CapacityK6Environment -Plan $Plan -RunDirectory $RunDirectory -SummaryPath $paths.k6SummaryPath -SecretPath $secretPath -MaxDurationSeconds $MaxDurationSeconds -MaxErrorRatePercent $MaxErrorRatePercent
        $execution = Invoke-CapacityLoggedProcess `
            -FileName "k6" `
            -ArgumentList @("run", "--summary-export", $paths.k6SummaryPath, $Plan.entrypoint) `
            -WorkingDirectory $repoRoot `
            -LogPath $paths.generationLogPath `
            -Environment $environment `
            -GuardProbe $liveGuardProbe
    } else {
        if ($null -eq (Get-Command "docker" -ErrorAction SilentlyContinue)) {
            throw "Docker is not available on PATH. Use -K6Mode local with k6 installed."
        }
        $environment = New-CapacityK6Environment -Plan $Plan -RunDirectory $RunDirectory -SummaryPath $paths.k6SummaryPath -SecretPath $secretPath -MaxDurationSeconds $MaxDurationSeconds -MaxErrorRatePercent $MaxErrorRatePercent -ContainerPaths
        $dockerArgs = @("run", "--rm", "-v", "$repoRoot`:/workspace", "-w", "/workspace")
        foreach ($key in $environment.Keys) {
            $dockerArgs += @("-e", "$key=$($environment[$key])")
        }
        $dockerArgs += @($Plan.toolImage, "run", "--summary-export", $environment.CAPACITY_SUMMARY_EXPORT, $Plan.entrypoint)
        $execution = Invoke-CapacityLoggedProcess `
            -FileName "docker" `
            -ArgumentList $dockerArgs `
            -WorkingDirectory $repoRoot `
            -LogPath $paths.generationLogPath `
            -GuardProbe $liveGuardProbe
    }

    $result = ConvertFrom-CapacityK6RestOutcomes -Plan $Plan -OutcomePath $paths.generationLogPath
    Add-Member -InputObject $result -NotePropertyName toolExecution -NotePropertyValue ([PSCustomObject]@{
        mode = $selectedMode
        exitCode = $execution.exitCode
        logPath = "generation-output.log"
        k6SummaryPath = if (Test-Path -LiteralPath $paths.k6SummaryPath) { "k6-summary.json" } else { $null }
    }) -Force
    if ($execution.status -ne "PASS" -and $result.status -eq "PASS") {
        $result.status = "INCONCLUSIVE"
    }
    if (@($execution.guardEvents).Count -gt 0) {
        Write-CapacityJsonFile -Value ([PSCustomObject]@{
            schemaVersion = 1
            status = "STOPPED_BY_GUARD"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            events = @($execution.guardEvents)
        }) -Path $paths.guardTimelinePath
    }

    Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $result | Out-Null
    $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
    $visibilityThreshold = if ($null -ne $summary -and $null -ne $summary.thresholds.visibilityP95Millis) { [double] $summary.thresholds.visibilityP95Millis } else { 5000 }
    $visibility = ConvertFrom-CapacityK6VisibilityOutcomes -OutcomePath $paths.generationLogPath -ThresholdMillis $visibilityThreshold
    Write-CapacityVisibilityObservation -RunDirectory $RunDirectory -Observation $visibility | Out-Null
    return [PSCustomObject]@{
        status = $result.status
        exitCode = $execution.exitCode
        generationResultPath = $paths.generationResultPath
        generationLogPath = $paths.generationLogPath
        visibilityPath = $paths.visibilityPath
    }
}

function Invoke-CapacityMqttGeneration {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Plan,
        [int] $MaxDurationSeconds = 0
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $repoRoot = Get-CapacityRepoRoot
    $powershellExe = if ($null -ne (Get-Command "pwsh" -ErrorAction SilentlyContinue)) { "pwsh" } else { "powershell" }
    $args = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $Plan.entrypoint,
        "-RunDirectory", $RunDirectory,
        "-WorkerIndex", [string] $Plan.workerIndex,
        "-Qos", [string] $Plan.mqtt.qos,
        "-ConnectionCount", [string] $Plan.mqtt.requestedConnections
    )
    if ($MaxDurationSeconds -gt 0) {
        $args += @("-MaxDurationSeconds", [string] $MaxDurationSeconds)
    }

    $execution = Invoke-CapacityLoggedProcess `
        -FileName $powershellExe `
        -ArgumentList $args `
        -WorkingDirectory $repoRoot `
        -LogPath $paths.generationLogPath

    $result = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
    if ($null -eq $result) {
        $result = New-CapacityGenerationResult -Plan $Plan -UnknownSequences @($Plan.readings | ForEach-Object { [int64] $_.sequence })
    }
    Add-Member -InputObject $result -NotePropertyName toolExecution -NotePropertyValue ([PSCustomObject]@{
        mode = "powershell"
        exitCode = $execution.exitCode
        logPath = "generation-output.log"
    }) -Force
    if ($execution.status -ne "PASS" -and $result.status -eq "PASS") {
        $result.status = "INCONCLUSIVE"
        Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $result | Out-Null
    }

    return [PSCustomObject]@{
        status = $result.status
        exitCode = $execution.exitCode
        generationResultPath = $paths.generationResultPath
        generationLogPath = $paths.generationLogPath
    }
}

function Invoke-CapacityProtocolGeneration {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [Parameter(Mandatory = $true)] [object] $Plan,
        [ValidateSet("auto", "local", "docker")] [string] $K6Mode = "auto",
        [int] $MaxDurationSeconds = 0,
        [double] $MinFreeDiskPercent = 20,
        [double] $MaxErrorRatePercent = 5,
        [int] $MaxLagGrowthSeconds = 300
    )

    if ($Plan.status -eq "BLOCKED") {
        throw "Generation plan is BLOCKED; unresolved identities must be fixed before execution."
    }

    if ($Plan.protocol -eq "rest") {
        return Invoke-CapacityRestGeneration -RunDirectory $RunDirectory -Plan $Plan -K6Mode $K6Mode -MaxDurationSeconds $MaxDurationSeconds -MinFreeDiskPercent $MinFreeDiskPercent -MaxErrorRatePercent $MaxErrorRatePercent -MaxLagGrowthSeconds $MaxLagGrowthSeconds
    }
    if ($Plan.protocol -eq "mqtt") {
        return Invoke-CapacityMqttGeneration -RunDirectory $RunDirectory -Plan $Plan -MaxDurationSeconds $MaxDurationSeconds
    }
    throw "Unsupported generation protocol '$($Plan.protocol)'."
}

function New-CapacityFaultRestoration {
    param(
        [string[]] $FaultTargets = @(),
        [switch] $DryRun
    )

    $targets = @($FaultTargets | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -Unique)
    return [PSCustomObject]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = if ($targets.Count -eq 0) { "NOT_REQUIRED" } elseif ($DryRun) { "PLANNED" } else { "REQUIRES_EXTERNAL_EXECUTION" }
        scope = "Only targets declared in this runner invocation are eligible for restoration."
        targets = @($targets | ForEach-Object {
            [PSCustomObject]@{
                target = $_
                action = if ($DryRun) { "planned-restore" } else { "restore-before-exit" }
            }
        })
    }
}

function Invoke-CapacityRunnerPhase {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [ValidateSet("prepare", "preflight", "generate", "reconcile", "report", "cleanup")] [string] $Phase = "generate",
        [ValidateSet("rest", "mqtt")] [string] $Protocol = "rest",
        [int] $WorkerIndex = 0,
        [int] $MaxStartedReadings = 0,
        [int] $MaxMaterializedReadings = 0,
        [int] $MaxDurationSeconds = 0,
        [double] $MinFreeDiskPercent = 0,
        [double] $ObservedErrorRatePercent = 0,
        [double] $MaxErrorRatePercent = 5,
        [int] $ObservedLagGrowthSeconds = 0,
        [int] $MaxLagGrowthSeconds = 300,
        [string[]] $FaultTargets = @(),
        [ValidateSet("auto", "local", "docker")] [string] $K6Mode = "auto",
        [switch] $DryRun,
        [bool] $AutoReconcile = $true
    )

    $paths = Get-CapacityRunPaths -RunDirectory $RunDirectory
    $guardEvents = @()
    $guardEvaluation = Test-CapacityRunnerGuards `
        -RunDirectory $RunDirectory `
        -MaxDurationSeconds $MaxDurationSeconds `
        -MinFreeDiskPercent $MinFreeDiskPercent `
        -ObservedErrorRatePercent $ObservedErrorRatePercent `
        -MaxErrorRatePercent $MaxErrorRatePercent `
        -ObservedLagGrowthSeconds $ObservedLagGrowthSeconds `
        -MaxLagGrowthSeconds $MaxLagGrowthSeconds
    $guardEvents = @($guardEvaluation.events)
    $stoppingGuardEvents = @($guardEvents | Where-Object { $_.action -eq "generation-stopped" })
    $plan = $null
    $generationResult = $null
    $protocolExecution = $null
    $automaticReconciliation = $null
    $status = if ($guardEvaluation.status -eq "STOP") { "INCONCLUSIVE" } else { "PASS" }

    if ($Phase -eq "generate") {
        $plan = New-CapacityGenerationPlan -RunDirectory $RunDirectory -Protocol $Protocol -WorkerIndex $WorkerIndex -MaxReadings $MaxMaterializedReadings
        Write-CapacityGenerationPlan -Plan $plan -RunDirectory $RunDirectory | Out-Null
        if (-not $DryRun -and $plan.identityResolution.source -ne "provisioning-state.json") {
            throw "Live generation requires provisioned API identities. Run provision-fixtures.ps1 for this run directory first."
        }
        $plannedReadings = @($plan.readings)
        $startedReadings = $plannedReadings
        if ($stoppingGuardEvents.Count -gt 0) {
            $startedReadings = @()
        } elseif ($MaxStartedReadings -gt 0 -and $MaxStartedReadings -lt $plannedReadings.Count) {
            $startedReadings = @($plannedReadings | Select-Object -First $MaxStartedReadings)
            $guardEvents += @([PSCustomObject]@{
                guard = "max-started-readings"
                limit = $MaxStartedReadings
                scheduled = $plannedReadings.Count
                action = "generation-stopped"
            })
            $stoppingGuardEvents = @($guardEvents | Where-Object { $_.action -eq "generation-stopped" })
            $status = "INCONCLUSIVE"
        }

        if ($stoppingGuardEvents.Count -gt 0 -or $DryRun) {
            $acceptedSequences = @()
            $generationResult = New-CapacityGenerationResult `
                -Plan $plan `
                -AcceptedSequences $acceptedSequences `
                -DroppedScheduledReadings ([int64] ($plannedReadings.Count - $startedReadings.Count))
            if ($stoppingGuardEvents.Count -eq 0) {
                $generationResult.status = "NOT_RUN"
            }
            Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $generationResult | Out-Null
            $status = $generationResult.status
        } else {
            try {
                $protocolExecution = Invoke-CapacityProtocolGeneration `
                    -RunDirectory $RunDirectory `
                    -Plan $plan `
                    -K6Mode $K6Mode `
                    -MaxDurationSeconds $MaxDurationSeconds `
                    -MinFreeDiskPercent $MinFreeDiskPercent `
                    -MaxErrorRatePercent $MaxErrorRatePercent `
                    -MaxLagGrowthSeconds $MaxLagGrowthSeconds
                $generationResult = Read-CapacityJsonFile -Path $paths.generationResultPath -Default $null
                $status = $protocolExecution.status
                if ($Protocol -eq "rest" -and $AutoReconcile) {
                    $schedule = Read-CapacityJsonFile -Path $paths.schedulePath
                    $automaticReconciliation = Invoke-CapacityAutomaticReconciliation -RunDirectory $RunDirectory -DrainSeconds ([int] $schedule.workload.drainSeconds)
                    if ($automaticReconciliation.status -ne "PASS") { $status = $automaticReconciliation.status }
                }
            } catch {
                $guardEvents += @([PSCustomObject]@{
                    guard = "generation-execution"
                    observed = $_.Exception.Message
                    action = "generation-stopped"
                })
                $stoppingGuardEvents = @($guardEvents | Where-Object { $_.action -eq "generation-stopped" })
                $generationResult = New-CapacityGenerationResult `
                    -Plan $plan `
                    -UnknownSequences @($plannedReadings | ForEach-Object { [int64] $_.sequence })
                $generationResult.status = "INCONCLUSIVE"
                Add-Member -InputObject $generationResult -NotePropertyName toolExecution -NotePropertyValue ([PSCustomObject]@{
                    mode = if ($Protocol -eq "rest") { $K6Mode } else { "powershell" }
                    exitCode = $null
                    logPath = if (Test-Path -LiteralPath $paths.generationLogPath) { "generation-output.log" } else { $null }
                    error = $_.Exception.Message
                }) -Force
                Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $generationResult | Out-Null
                $status = "INCONCLUSIVE"
            }
        }
    } elseif ($Phase -eq "prepare") {
        $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
        $runId = if ($null -eq $summary) { (Split-Path -Leaf $RunDirectory) } else { $summary.runId }
        $manifest = New-CapacityEnvironmentManifest -RunId $runId
        Write-CapacityJsonFile -Value $manifest -Path $paths.environmentManifestPath
        $observability = New-CapacityObservabilityInventory -RunId $runId
        Write-CapacityObservabilityInventory -Inventory $observability -RunDirectory $RunDirectory | Out-Null
        $status = if ($DryRun) { "DRY_RUN" } else { "PASS" }
    } elseif ($Phase -eq "preflight") {
        $summary = Read-CapacityJsonFile -Path $paths.summaryPath -Default $null
        $status = if ($null -eq $summary) { "NOT_RUN" } else { $summary.status }
    } elseif ($Phase -eq "reconcile") {
        $reconciliation = Read-CapacityJsonFile -Path $paths.reconciliationPath -Default $null
        $status = if ($null -eq $reconciliation) { "NOT_RUN" } else { $reconciliation.status }
    } elseif ($Phase -eq "report") {
        $report = Update-CapacityRunReport -RunDirectory $RunDirectory
        $status = $report.status
    } elseif ($Phase -eq "cleanup") {
        if ($DryRun) {
            $cleanup = Invoke-CapacityFixtureCleanup -RunDirectory $RunDirectory -DryRun
            $status = $cleanup.status
        } else {
            throw "Cleanup without -DryRun requires explicit API credentials; call Invoke-CapacityFixtureCleanup with scoped credentials."
        }
    }

    $stoppingGuardEvents = @($guardEvents | Where-Object { $_.action -eq "generation-stopped" })
    if ($stoppingGuardEvents.Count -gt 0) {
        $faultRestoration = New-CapacityFaultRestoration -FaultTargets $FaultTargets -DryRun:$DryRun
        Write-CapacityJsonFile -Value $faultRestoration -Path $paths.faultRestorationPath
        $cancellation = [PSCustomObject]@{
            schemaVersion = 1
            runId = if ($null -eq $plan) { (Split-Path -Leaf $RunDirectory) } else { $plan.runId }
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            status = "STOPPED_BY_GUARD"
            guardEvents = @($stoppingGuardEvents)
            guardEvaluation = $guardEvaluation
            drainPolicy = "Run a bounded drain and reconciliation after guard-triggered stop when services were contacted."
            restorationPolicy = "Only fault targets created by this runner may be restored."
            faultRestorationPath = "fault-restoration.json"
        }
        Write-CapacityJsonFile -Value $cancellation -Path $paths.cancellationReportPath
    }

    $runnerSummary = [PSCustomObject]@{
        schemaVersion = 1
        runId = if ($null -eq $plan) { (Split-Path -Leaf $RunDirectory) } else { $plan.runId }
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        phase = $Phase
        status = $status
        dryRun = [bool] $DryRun
        protocol = $Protocol
        generationManifestPath = if (Test-Path -LiteralPath $paths.generationManifestPath) { "generation-manifest.json" } else { $null }
        generationResultPath = if (Test-Path -LiteralPath $paths.generationResultPath) { "generation-result.json" } else { $null }
        generationLogPath = if (Test-Path -LiteralPath $paths.generationLogPath) { "generation-output.log" } else { $null }
        cancellationReportPath = if (Test-Path -LiteralPath $paths.cancellationReportPath) { "cancellation-report.json" } else { $null }
        faultRestorationPath = if (Test-Path -LiteralPath $paths.faultRestorationPath) { "fault-restoration.json" } else { $null }
        environmentManifestPath = if (Test-Path -LiteralPath $paths.environmentManifestPath) { "environment-manifest.json" } else { $null }
        observabilityInventoryPath = if (Test-Path -LiteralPath $paths.observabilityInventoryPath) { "observability-inventory.json" } else { $null }
        protocolExecution = $protocolExecution
        automaticReconciliation = $automaticReconciliation
        guards = $guardEvaluation
    }
    Write-CapacityJsonFile -Value $runnerSummary -Path $paths.runnerSummaryPath
    Update-CapacityRunReport -RunDirectory $RunDirectory | Out-Null
    return [PSCustomObject]@{
        status = $status
        runDirectory = $RunDirectory
        runnerSummaryPath = $paths.runnerSummaryPath
        cancellationReportPath = if (Test-Path -LiteralPath $paths.cancellationReportPath) { $paths.cancellationReportPath } else { $null }
    }
}

function New-CapacityProvisioningState {
    param(
        [Parameter(Mandatory = $true)] [object] $Fixture,
        [Parameter(Mandatory = $true)] [string] $DeviceApiBaseUrl,
        [int] $LimitDevices = 0,
        [bool] $DryRun = $false
    )

    $devices = @($Fixture.devices)
    if ($LimitDevices -gt 0) {
        $devices = @($devices | Select-Object -First $LimitDevices)
    }
    $tenantIndexes = @($devices | Select-Object -ExpandProperty tenantIndex -Unique)
    $tenants = @($Fixture.tenants | Where-Object { $tenantIndexes -contains $_.tenantIndex })

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $Fixture.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        deviceApiBaseUrl = (ConvertTo-CapacityApiBaseUrl -BaseUrl $DeviceApiBaseUrl)
        dryRun = $DryRun
        status = if ($DryRun) { "DRY_RUN" } else { "IN_PROGRESS" }
        fixtureManifestPath = "fixture-manifest.json"
        scope = @{
            tenantCount = @($tenants).Count
            requestedDeviceCount = $Fixture.deviceCount
            selectedDeviceCount = @($devices).Count
            sourceCount = @($devices).Count
        }
        auth = @{
            mode = "Bearer token when supplied, otherwise direct local X-User headers."
            bearerTokenPersisted = $false
            sourceTokensPersistedInState = $false
        }
        locations = @()
        devices = @()
        dataSources = @()
        tokenRotations = @()
        readiness = @()
        cleanup = @{
            status = "NOT_RUN"
            deletedDataSources = 0
            decommissionedDevices = 0
        }
        failures = @()
    }
}

function Get-CapacityProvisioningPaths {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string] $SecretOutputPath = ""
    )

    $secretPath = $SecretOutputPath
    if ($secretPath.Trim().Length -eq 0) {
        $secretPath = Join-Path $RunDirectory "provisioning-secrets.local.json"
    }
    return [PSCustomObject]@{
        fixturePath = Join-Path $RunDirectory "fixture-manifest.json"
        statePath = Join-Path $RunDirectory "provisioning-state.json"
        summaryPath = Join-Path $RunDirectory "provisioning-summary.json"
        secretPath = $secretPath
    }
}

function Get-CapacityTenantEmail {
    param(
        [Parameter(Mandatory = $true)] [object] $Fixture,
        [Parameter(Mandatory = $true)] [object] $Tenant
    )

    return "capacity+$($Fixture.runId)-tenant-$($Tenant.tenantIndex)@load.local"
}

function Get-CapacityProvisionedLocationName {
    param(
        [Parameter(Mandatory = $true)] [object] $Fixture,
        [Parameter(Mandatory = $true)] [object] $Tenant
    )

    return "capacity-$($Fixture.runId)-tenant-$($Tenant.tenantIndex)"
}

function Add-CapacityStateEntry {
    param(
        [object[]] $Entries = @(),
        [Parameter(Mandatory = $true)] [object] $Entry,
        [Parameter(Mandatory = $true)] [string] $KeyName,
        [Parameter(Mandatory = $true)] [object] $KeyValue
    )

    $existing = @($Entries | Where-Object { $_.$KeyName -eq $KeyValue } | Select-Object -First 1)
    if ($existing.Count -gt 0) {
        return @($Entries)
    }
    return @($Entries + $Entry)
}

function Get-CapacityStateEntry {
    param(
        [object[]] $Entries = @(),
        [Parameter(Mandatory = $true)] [string] $KeyName,
        [Parameter(Mandatory = $true)] [object] $KeyValue
    )

    return @($Entries | Where-Object { $_.$KeyName -eq $KeyValue } | Select-Object -First 1)
}

function Invoke-CapacityParallelDeviceProvisioning {
    param(
        [Parameter(Mandatory = $true)] [object[]] $Devices,
        [Parameter(Mandatory = $true)] [object[]] $Tenants,
        [Parameter(Mandatory = $true)] [object] $State,
        [Parameter(Mandatory = $true)] [object] $Secrets,
        [Parameter(Mandatory = $true)] [string] $DeviceApiBaseUrl,
        [string] $BearerToken = "",
        [ValidateSet("REST", "MQTT")] [string] $SourceProtocol = "REST",
        [switch] $RotateSourceTokens,
        [ValidateRange(2, 64)] [int] $Parallelism = 16,
        [int] $TimeoutSeconds = 30,
        [Parameter(Mandatory = $true)] [string] $StatePath,
        [Parameter(Mandatory = $true)] [string] $SecretPath
    )

    $tenantByIndex = @{}
    foreach ($tenant in $Tenants) { $tenantByIndex[[int] $tenant.tenantIndex] = $tenant }
    $locationByTenant = @{}
    foreach ($location in @($State.locations)) { $locationByTenant[[int] $location.tenantIndex] = $location }
    $deviceByIndex = @{}
    foreach ($entry in @($State.devices)) { $deviceByIndex[[int] $entry.deviceIndex] = $entry }
    $sourceByIndex = @{}
    foreach ($entry in @($State.dataSources)) { $sourceByIndex[[int] $entry.deviceIndex] = $entry }
    $rotationByIndex = @{}
    foreach ($entry in @($State.tokenRotations)) { $rotationByIndex[[int] $entry.deviceIndex] = $entry }
    $secretByIndex = @{}
    foreach ($entry in @($Secrets.sourceTokens)) { $secretByIndex[[int] $entry.deviceIndex] = $entry }

    $workItems = [System.Collections.Generic.List[object]]::new()
    foreach ($device in $Devices) {
        $index = [int] $device.deviceIndex
        $hasToken = (-not $RotateSourceTokens) -or ($rotationByIndex.ContainsKey($index) -and $secretByIndex.ContainsKey($index))
        if ($deviceByIndex.ContainsKey($index) -and $sourceByIndex.ContainsKey($index) -and $hasToken) { continue }
        $workItems.Add([PSCustomObject]@{
            device = $device
            tenant = $tenantByIndex[[int] $device.tenantIndex]
            locationId = $locationByTenant[[int] $device.tenantIndex].locationId
            existingDevice = if ($deviceByIndex.ContainsKey($index)) { $deviceByIndex[$index] } else { $null }
            existingSource = if ($sourceByIndex.ContainsKey($index)) { $sourceByIndex[$index] } else { $null }
            existingSecret = if ($secretByIndex.ContainsKey($index)) { $secretByIndex[$index] } else { $null }
        })
    }

    $baseUrl = ConvertTo-CapacityApiBaseUrl -BaseUrl $DeviceApiBaseUrl
    $runId = [string] $State.runId
    $rotateTokens = [bool] $RotateSourceTokens
    $bearerSupplied = $BearerToken.Trim().Length -gt 0
    $batchSize = [math]::Max(100, $Parallelism * 8)
    for ($offset = 0; $offset -lt $workItems.Count; $offset += $batchSize) {
        $last = [math]::Min($offset + $batchSize - 1, $workItems.Count - 1)
        $batch = @($workItems[$offset..$last])
        $results = @($batch | ForEach-Object -ThrottleLimit $Parallelism -Parallel {
            $item = $_
            $device = $item.device
            $tenant = $item.tenant
            if ($null -eq $script:CapacityProvisioningHttpClient) {
                $script:CapacityProvisioningHttpClient = [System.Net.Http.HttpClient]::new()
                $script:CapacityProvisioningHttpClient.Timeout = [TimeSpan]::FromSeconds($using:TimeoutSeconds)
            }
            function Invoke-ParallelProvisioningJson {
                param([string] $Method, [string] $Uri, [hashtable] $Headers, [AllowNull()] [string] $Body = $null)
                $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Uri)
                try {
                    foreach ($header in $Headers.GetEnumerator()) { $request.Headers.TryAddWithoutValidation([string] $header.Key, [string] $header.Value) | Out-Null }
                    if ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json") }
                    $response = $script:CapacityProvisioningHttpClient.SendAsync($request).GetAwaiter().GetResult()
                    try {
                        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                        if (-not $response.IsSuccessStatusCode) { throw "HTTP $([int] $response.StatusCode) $($response.ReasonPhrase): $content" }
                        if ($content.Trim().Length -eq 0) { return $null }
                        return $content | ConvertFrom-Json
                    } finally { $response.Dispose() }
                } finally { $request.Dispose() }
            }
            $headers = @{ Accept = "application/json"; "Content-Type" = "application/json"; "X-Correlation-Id" = "capacity-provisioning-$($tenant.accountId)" }
            if ($using:bearerSupplied) { $headers.Authorization = "Bearer $using:BearerToken" }
            else {
                $headers["X-User-Id"] = [string] $tenant.accountId
                $headers["X-User-Email"] = "capacity+$using:runId-tenant-$($tenant.tenantIndex)@load.local"
                $headers["X-User-Role"] = "USER"
            }
            try {
                $apiDeviceId = if ($null -ne $item.existingDevice) { $item.existingDevice.apiDeviceId } else { $null }
                if ($null -eq $apiDeviceId) {
                    $body = @{ locationId = $item.locationId; name = $device.deviceName; type = "FRIDGE"; manufacturer = "VoltCanary"; model = "CapacityFixture"; metadata = @{ capacityRunId = $using:runId; fixtureDeviceIndex = $device.deviceIndex } } | ConvertTo-Json -Depth 10
                    $created = Invoke-ParallelProvisioningJson -Method POST -Uri "$using:baseUrl/devices" -Headers $headers -Body $body
                    $apiDeviceId = $created.id
                }
                $apiDataSourceId = if ($null -ne $item.existingSource) { $item.existingSource.apiDataSourceId } else { $null }
                if ($null -eq $apiDataSourceId) {
                    $body = @{ sourceType = "SMART_PLUG"; protocol = $using:SourceProtocol; externalId = $device.sourceName; mapping = @{}; adapterConfig = @{}; credentialReferences = @{}; homeRegion = "local" } | ConvertTo-Json -Depth 10
                    $createdSource = Invoke-ParallelProvisioningJson -Method POST -Uri "$using:baseUrl/devices/$apiDeviceId/data-sources" -Headers $headers -Body $body
                    $apiDataSourceId = $createdSource.id
                }
                $tokenPrefix = if ($null -ne $item.existingSecret) { $item.existingSecret.tokenPrefix } else { $null }
                $token = if ($null -ne $item.existingSecret) { $item.existingSecret.token } else { $null }
                if ($using:rotateTokens -and $null -eq $token) {
                    $tokenResponse = Invoke-ParallelProvisioningJson -Method POST -Uri "$using:baseUrl/devices/$apiDeviceId/data-sources/$apiDataSourceId/token" -Headers $headers
                    $tokenPrefix = $tokenResponse.tokenPrefix; $token = $tokenResponse.token
                }
                [PSCustomObject]@{ status = "PASS"; deviceIndex = [int] $device.deviceIndex; tenantIndex = [int] $device.tenantIndex; accountId = $device.accountId; fixtureDeviceId = $device.deviceId; apiDeviceId = $apiDeviceId; name = $device.deviceName; apiDataSourceId = $apiDataSourceId; fixtureSourceId = $device.sourceId; externalId = $device.sourceName; protocol = $using:SourceProtocol; tokenPrefix = $tokenPrefix; token = $token }
            } catch { [PSCustomObject]@{ status = "FAIL"; deviceIndex = [int] $device.deviceIndex; message = $_.Exception.Message } }
        })

        $failures = @($results | Where-Object { $_.status -eq "FAIL" })
        foreach ($failure in $failures) { $State.failures = @($State.failures) + [PSCustomObject]@{ deviceIndex = $failure.deviceIndex; stage = "parallel-provisioning"; message = $failure.message } }
        foreach ($result in @($results | Where-Object { $_.status -eq "PASS" })) {
            $index = [int] $result.deviceIndex
            $State.failures = @($State.failures | Where-Object { [int] $_.deviceIndex -ne $index -or $_.stage -ne "parallel-provisioning" })
            if (-not $deviceByIndex.ContainsKey($index)) {
                $entry = [PSCustomObject]@{ deviceIndex = $index; tenantIndex = $result.tenantIndex; accountId = $result.accountId; fixtureDeviceId = $result.fixtureDeviceId; apiDeviceId = $result.apiDeviceId; name = $result.name; status = "CREATED" }
                $deviceByIndex[$index] = $entry; $State.devices = @($State.devices) + $entry
            }
            if (-not $sourceByIndex.ContainsKey($index)) {
                $entry = [PSCustomObject]@{ deviceIndex = $index; tenantIndex = $result.tenantIndex; accountId = $result.accountId; apiDeviceId = $result.apiDeviceId; apiDataSourceId = $result.apiDataSourceId; fixtureSourceId = $result.fixtureSourceId; externalId = $result.externalId; protocol = $result.protocol; status = "CREATED" }
                $sourceByIndex[$index] = $entry; $State.dataSources = @($State.dataSources) + $entry
            }
            if ($RotateSourceTokens -and -not $rotationByIndex.ContainsKey($index)) {
                $rotation = [PSCustomObject]@{ deviceIndex = $index; apiDataSourceId = $result.apiDataSourceId; tokenPrefix = $result.tokenPrefix; tokenPersistedTo = Split-Path -Leaf $SecretPath; status = "ROTATED" }
                $secret = [PSCustomObject]@{ deviceIndex = $index; apiDeviceId = $result.apiDeviceId; apiDataSourceId = $result.apiDataSourceId; externalId = $result.externalId; tokenPrefix = $result.tokenPrefix; token = $result.token }
                $rotationByIndex[$index] = $rotation; $secretByIndex[$index] = $secret
                $State.tokenRotations = @($State.tokenRotations) + $rotation; $Secrets.sourceTokens = @($Secrets.sourceTokens) + $secret
            }
        }
        $State.generatedAt = (Get-Date).ToUniversalTime().ToString("o"); $Secrets.generatedAt = $State.generatedAt
        Write-CapacityJsonFile -Value $State -Path $StatePath
        if ($RotateSourceTokens) { Write-CapacityJsonFile -Value $Secrets -Path $SecretPath }
        Write-Host "Provisioned $([math]::Min($last + 1, $workItems.Count))/$($workItems.Count) pending devices (parallelism $Parallelism)."
        if ($failures.Count -gt 0) { throw "Parallel provisioning failed for $($failures.Count) device(s) in the latest checkpoint; rerun to resume." }
    }
    return [PSCustomObject]@{ state = $State; secrets = $Secrets }
}

function Invoke-CapacityFixtureProvisioning {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string] $DeviceApiBaseUrl = "http://localhost:8082",
        [string[]] $IngestionBaseUrls = @(),
        [string] $BearerToken = "",
        [string] $InternalServiceToken = "dev-internal-token",
        [int] $LimitDevices = 0,
        [ValidateRange(1, 64)] [int] $Parallelism = 1,
        [ValidateSet("REST", "MQTT")] [string] $SourceProtocol = "REST",
        [switch] $RotateSourceTokens,
        [string] $SecretOutputPath = "",
        [switch] $DryRun,
        [int] $TimeoutSeconds = 30
    )

    $paths = Get-CapacityProvisioningPaths -RunDirectory $RunDirectory -SecretOutputPath $SecretOutputPath
    if (-not (Test-Path -LiteralPath $paths.fixturePath)) {
        throw "fixture-manifest.json not found in run directory: $RunDirectory"
    }

    $fixture = Get-Content -Raw -LiteralPath $paths.fixturePath | ConvertFrom-Json
    $state = Read-CapacityJsonFile -Path $paths.statePath -Default $null
    if ($null -eq $state) {
        $state = New-CapacityProvisioningState `
            -Fixture $fixture `
            -DeviceApiBaseUrl $DeviceApiBaseUrl `
            -LimitDevices $LimitDevices `
            -DryRun ([bool] $DryRun)
    }

    $devices = @($fixture.devices)
    if ($LimitDevices -gt 0) {
        $devices = @($devices | Select-Object -First $LimitDevices)
    }
    $tenantIndexes = @($devices | Select-Object -ExpandProperty tenantIndex -Unique)
    $tenants = @($fixture.tenants | Where-Object { $tenantIndexes -contains $_.tenantIndex })
    $secrets = Read-CapacityJsonFile -Path $paths.secretPath -Default $null
    if ($null -eq $secrets) {
        $secrets = [PSCustomObject]@{
            schemaVersion = 1
            runId = $fixture.runId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            note = "Local ignored file for generator credentials; do not commit or copy into reports."
            sourceTokens = @()
        }
    }

    foreach ($tenant in $tenants) {
        $locationName = Get-CapacityProvisionedLocationName -Fixture $fixture -Tenant $tenant
        $knownLocation = Get-CapacityStateEntry -Entries @($state.locations) -KeyName "tenantIndex" -KeyValue $tenant.tenantIndex
        if ($knownLocation.Count -eq 0) {
            $locationId = if ($DryRun) { New-CapacityStableGuid -Value "$($fixture.runId):api-location:$($tenant.tenantIndex)" } else { $null }
            if (-not $DryRun) {
                $headers = New-CapacityApiHeaders `
                    -UserId $tenant.accountId `
                    -Email (Get-CapacityTenantEmail -Fixture $fixture -Tenant $tenant) `
                    -BearerToken $BearerToken
                Invoke-CapacityApiJson -Method "GET" -BaseUrl $DeviceApiBaseUrl -Path "/accounts/current" -Headers $headers -TimeoutSeconds $TimeoutSeconds | Out-Null
                $location = Invoke-CapacityApiJson `
                    -Method "POST" `
                    -BaseUrl $DeviceApiBaseUrl `
                    -Path "/locations" `
                    -Headers $headers `
                    -TimeoutSeconds $TimeoutSeconds `
                    -Body @{
                        name = $locationName
                        description = "Capacity verification fixture $($fixture.runId), tenant $($tenant.tenantIndex)."
                        tags = @("capacity-verification", $fixture.runId)
                    }
                $locationId = $location.id
            }

            $entry = [PSCustomObject]@{
                tenantIndex = $tenant.tenantIndex
                accountId = $tenant.accountId
                userEmail = Get-CapacityTenantEmail -Fixture $fixture -Tenant $tenant
                locationId = $locationId
                name = $locationName
                status = if ($DryRun) { "PLANNED" } else { "CREATED" }
            }
            $state.locations = Add-CapacityStateEntry -Entries @($state.locations) -Entry $entry -KeyName "tenantIndex" -KeyValue $tenant.tenantIndex
            Write-CapacityJsonFile -Value $state -Path $paths.statePath
        }
    }

    if ($Parallelism -gt 1 -and -not $DryRun) {
        $parallelResult = Invoke-CapacityParallelDeviceProvisioning `
            -Devices $devices `
            -Tenants $tenants `
            -State $state `
            -Secrets $secrets `
            -DeviceApiBaseUrl $DeviceApiBaseUrl `
            -BearerToken $BearerToken `
            -SourceProtocol $SourceProtocol `
            -RotateSourceTokens:$RotateSourceTokens `
            -Parallelism $Parallelism `
            -TimeoutSeconds $TimeoutSeconds `
            -StatePath $paths.statePath `
            -SecretPath $paths.secretPath
        $state = $parallelResult.state
        $secrets = $parallelResult.secrets
    } else {
    foreach ($device in $devices) {
        $knownDevice = Get-CapacityStateEntry -Entries @($state.devices) -KeyName "deviceIndex" -KeyValue $device.deviceIndex
        if ($knownDevice.Count -eq 0) {
            $tenant = @($tenants | Where-Object { $_.tenantIndex -eq $device.tenantIndex } | Select-Object -First 1)[0]
            $location = @($state.locations | Where-Object { $_.tenantIndex -eq $device.tenantIndex } | Select-Object -First 1)[0]
            $apiDeviceId = if ($DryRun) { $device.deviceId } else { $null }
            if (-not $DryRun) {
                $headers = New-CapacityApiHeaders `
                    -UserId $tenant.accountId `
                    -Email (Get-CapacityTenantEmail -Fixture $fixture -Tenant $tenant) `
                    -BearerToken $BearerToken
                $createdDevice = Invoke-CapacityApiJson `
                    -Method "POST" `
                    -BaseUrl $DeviceApiBaseUrl `
                    -Path "/devices" `
                    -Headers $headers `
                    -TimeoutSeconds $TimeoutSeconds `
                    -Body @{
                        locationId = $location.locationId
                        name = $device.deviceName
                        type = "FRIDGE"
                        manufacturer = "VoltCanary"
                        model = "CapacityFixture"
                        metadata = @{
                            capacityRunId = $fixture.runId
                            fixtureDeviceIndex = $device.deviceIndex
                        }
                    }
                $apiDeviceId = $createdDevice.id
            }

            $entry = [PSCustomObject]@{
                deviceIndex = $device.deviceIndex
                tenantIndex = $device.tenantIndex
                accountId = $device.accountId
                fixtureDeviceId = $device.deviceId
                apiDeviceId = $apiDeviceId
                name = $device.deviceName
                status = if ($DryRun) { "PLANNED" } else { "CREATED" }
            }
            $state.devices = Add-CapacityStateEntry -Entries @($state.devices) -Entry $entry -KeyName "deviceIndex" -KeyValue $device.deviceIndex
            Write-CapacityJsonFile -Value $state -Path $paths.statePath
        }

        $knownSource = Get-CapacityStateEntry -Entries @($state.dataSources) -KeyName "deviceIndex" -KeyValue $device.deviceIndex
        if ($knownSource.Count -eq 0) {
            $tenant = @($tenants | Where-Object { $_.tenantIndex -eq $device.tenantIndex } | Select-Object -First 1)[0]
            $stateDevice = @($state.devices | Where-Object { $_.deviceIndex -eq $device.deviceIndex } | Select-Object -First 1)[0]
            $apiDataSourceId = if ($DryRun) { New-CapacityStableGuid -Value "$($fixture.runId):api-source:$($device.deviceIndex)" } else { $null }
            if (-not $DryRun) {
                $headers = New-CapacityApiHeaders `
                    -UserId $tenant.accountId `
                    -Email (Get-CapacityTenantEmail -Fixture $fixture -Tenant $tenant) `
                    -BearerToken $BearerToken
                $createdSource = Invoke-CapacityApiJson `
                    -Method "POST" `
                    -BaseUrl $DeviceApiBaseUrl `
                    -Path "/devices/$($stateDevice.apiDeviceId)/data-sources" `
                    -Headers $headers `
                    -TimeoutSeconds $TimeoutSeconds `
                    -Body @{
                        sourceType = "SMART_PLUG"
                        protocol = $SourceProtocol
                        externalId = $device.sourceName
                        mapping = @{}
                        adapterConfig = @{}
                        credentialReferences = @{}
                        homeRegion = "local"
                    }
                $apiDataSourceId = $createdSource.id
            }

            $entry = [PSCustomObject]@{
                deviceIndex = $device.deviceIndex
                tenantIndex = $device.tenantIndex
                accountId = $device.accountId
                apiDeviceId = $stateDevice.apiDeviceId
                apiDataSourceId = $apiDataSourceId
                fixtureSourceId = $device.sourceId
                externalId = $device.sourceName
                protocol = $SourceProtocol
                status = if ($DryRun) { "PLANNED" } else { "CREATED" }
            }
            $state.dataSources = Add-CapacityStateEntry -Entries @($state.dataSources) -Entry $entry -KeyName "deviceIndex" -KeyValue $device.deviceIndex
            Write-CapacityJsonFile -Value $state -Path $paths.statePath
        }

        if ($RotateSourceTokens) {
            $existingRotation = Get-CapacityStateEntry -Entries @($state.tokenRotations) -KeyName "deviceIndex" -KeyValue $device.deviceIndex
            $existingSecret = @($secrets.sourceTokens | Where-Object { [int] $_.deviceIndex -eq [int] $device.deviceIndex } | Select-Object -First 1)
            if ($existingRotation.Count -gt 0 -and -not $DryRun -and $existingSecret.Count -eq 0) {
                $state.tokenRotations = @($state.tokenRotations | Where-Object { [int] $_.deviceIndex -ne [int] $device.deviceIndex })
                $existingRotation = @()
            }
            if ($existingRotation.Count -eq 0) {
                $tenant = @($tenants | Where-Object { $_.tenantIndex -eq $device.tenantIndex } | Select-Object -First 1)[0]
                $stateDevice = @($state.devices | Where-Object { $_.deviceIndex -eq $device.deviceIndex } | Select-Object -First 1)[0]
                $stateSource = @($state.dataSources | Where-Object { $_.deviceIndex -eq $device.deviceIndex } | Select-Object -First 1)[0]
                $tokenPrefix = if ($DryRun) { "dry-run-token-prefix-$($device.deviceIndex)" } else { $null }
                $token = if ($DryRun) { "dry-run-token-redacted" } else { $null }
                if (-not $DryRun) {
                    $headers = New-CapacityApiHeaders `
                        -UserId $tenant.accountId `
                        -Email (Get-CapacityTenantEmail -Fixture $fixture -Tenant $tenant) `
                        -BearerToken $BearerToken
                    $tokenResponse = Invoke-CapacityApiJson `
                        -Method "POST" `
                        -BaseUrl $DeviceApiBaseUrl `
                        -Path "/devices/$($stateDevice.apiDeviceId)/data-sources/$($stateSource.apiDataSourceId)/token" `
                        -Headers $headers `
                        -TimeoutSeconds $TimeoutSeconds
                    $tokenPrefix = $tokenResponse.tokenPrefix
                    $token = $tokenResponse.token
                }

                $secrets.sourceTokens = @($secrets.sourceTokens | Where-Object { [int] $_.deviceIndex -ne [int] $device.deviceIndex })
                $secrets.sourceTokens = @($secrets.sourceTokens + [PSCustomObject]@{
                    deviceIndex = $device.deviceIndex
                    apiDeviceId = $stateDevice.apiDeviceId
                    apiDataSourceId = $stateSource.apiDataSourceId
                    externalId = $stateSource.externalId
                    tokenPrefix = $tokenPrefix
                    token = $token
                })
                $secrets.generatedAt = (Get-Date).ToUniversalTime().ToString("o")
                if (-not $DryRun) {
                    Write-CapacityJsonFile -Value $secrets -Path $paths.secretPath
                }

                $state.tokenRotations = Add-CapacityStateEntry `
                    -Entries @($state.tokenRotations) `
                    -Entry ([PSCustomObject]@{
                        deviceIndex = $device.deviceIndex
                        apiDataSourceId = $stateSource.apiDataSourceId
                        tokenPrefix = $tokenPrefix
                        tokenPersistedTo = if ($DryRun) { "DRY_RUN" } else { Split-Path -Leaf $paths.secretPath }
                        status = if ($DryRun) { "PLANNED" } else { "ROTATED" }
                    }) `
                    -KeyName "deviceIndex" `
                    -KeyValue $device.deviceIndex
                Write-CapacityJsonFile -Value $state -Path $paths.statePath
            }
        }
    }
    }

    if ($RotateSourceTokens -and -not $DryRun) {
        Write-CapacityJsonFile -Value $secrets -Path $paths.secretPath
    }

    $readiness = Wait-CapacitySourceRuntimeReadiness `
        -RunDirectory $RunDirectory `
        -IngestionBaseUrls $IngestionBaseUrls `
        -InternalServiceToken $InternalServiceToken `
        -TimeoutSeconds $TimeoutSeconds `
        -DryRun:$DryRun
    $state.readiness = @($readiness)
    $readinessFailures = @($state.readiness | Where-Object { $_.status -eq "TIMEOUT" })
    $state.status = if ($state.failures.Count -gt 0) { "FAILED" } elseif ($DryRun) { "DRY_RUN" } elseif ($readinessFailures.Count -gt 0) { "BLOCKED" } else { "PROVISIONED" }
    $state.generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    Write-CapacityJsonFile -Value $state -Path $paths.statePath

    $summary = [PSCustomObject]@{
        schemaVersion = 1
        runId = $fixture.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = $state.status
        dryRun = [bool] $DryRun
        locations = @($state.locations).Count
        devices = @($state.devices).Count
        dataSources = @($state.dataSources).Count
        tokenRotations = @($state.tokenRotations).Count
        parallelism = $Parallelism
        readiness = @($state.readiness)
        statePath = "provisioning-state.json"
        secretPath = if ($RotateSourceTokens -and -not $DryRun) { Split-Path -Leaf $paths.secretPath } else { "NOT_WRITTEN" }
    }
    Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
    return [PSCustomObject]@{
        status = $summary.status
        runDirectory = $RunDirectory
        statePath = $paths.statePath
        summaryPath = $paths.summaryPath
        secretPath = if ($RotateSourceTokens -and -not $DryRun) { $paths.secretPath } else { $null }
    }
}

function Wait-CapacitySourceRuntimeReadiness {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string[]] $IngestionBaseUrls = @(),
        [string] $InternalServiceToken = "dev-internal-token",
        [int] $TimeoutSeconds = 30,
        [switch] $DryRun
    )

    $paths = Get-CapacityProvisioningPaths -RunDirectory $RunDirectory
    $state = Read-CapacityJsonFile -Path $paths.statePath -Default $null
    if ($null -eq $state) {
        return @()
    }

    $checks = @()
    foreach ($baseUrl in $IngestionBaseUrls) {
        if ($baseUrl.Trim().Length -eq 0) {
            continue
        }
        $base = ConvertTo-CapacityApiBaseUrl -BaseUrl $baseUrl
        if ($DryRun) {
            $checks += [PSCustomObject]@{
                ingestionBaseUrl = $base
                status = "PLANNED"
                readinessEndpoint = "/actuator/health/readiness"
                sampledSources = @()
            }
            continue
        }

        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $healthStatus = "UNAVAILABLE"
        $sourceStates = @()
        $ready = $false
        do {
            try {
                $health = Invoke-CapacityApiJson -Method "GET" -BaseUrl $base -Path "/actuator/health/readiness" -TimeoutSeconds 5
                $healthStatus = if ($health.status) { $health.status } else { "OBSERVED" }
            } catch {
                $healthStatus = "UNAVAILABLE: $($_.Exception.Message)"
            }

            $sourceStates = @()
            try {
                $firstSource = @($state.dataSources | Select-Object -First 1)[0]
                $headers = New-CapacityApiHeaders `
                    -UserId $firstSource.accountId `
                    -Email "capacity-readiness@$($state.runId).local" `
                    -InternalServiceToken $InternalServiceToken
                $secretDocument = Read-CapacityJsonFile -Path $paths.secretPath -Default $null
                $tokenPrefixByDataSourceId = @{}
                foreach ($token in @($secretDocument.sourceTokens)) {
                    if ($null -ne $token.apiDataSourceId -and -not [string]::IsNullOrWhiteSpace([string] $token.tokenPrefix)) {
                        $tokenPrefixByDataSourceId[[string] $token.apiDataSourceId] = [string] $token.tokenPrefix
                    }
                }
                $sourceReadiness = Invoke-CapacityApiJson `
                    -Method "POST" `
                    -BaseUrl $base `
                    -Path "/internal/ingestion/horizontal-scale-smoke/sources/readiness" `
                    -Headers $headers `
                    -Body @{
                        dataSourceIds = @($state.dataSources | ForEach-Object { $_.apiDataSourceId })
                        expectedTokenPrefixes = $tokenPrefixByDataSourceId
                    } `
                    -TimeoutSeconds ([math]::Max(10, $TimeoutSeconds))
                $sourceStates = @([PSCustomObject]@{
                    instanceId = $sourceReadiness.instanceId
                    requestedCount = $sourceReadiness.requestedCount
                    knownSourceCount = $sourceReadiness.knownSourceCount
                    knownRuntimeConfigCount = $sourceReadiness.knownRuntimeConfigCount
                    matchingTokenPrefixCount = $sourceReadiness.matchingTokenPrefixCount
                    ready = $sourceReadiness.ready
                    missingSourceIds = @($sourceReadiness.missingSourceIds)
                    missingRuntimeConfigIds = @($sourceReadiness.missingRuntimeConfigIds)
                    tokenPrefixMismatchIds = @($sourceReadiness.tokenPrefixMismatchIds)
                })
                $ready = ($healthStatus -eq "UP" -or $healthStatus -eq "OBSERVED") -and $sourceReadiness.ready -eq $true
            } catch {
                $sourceStates = @([PSCustomObject]@{ status = "UNAVAILABLE"; error = $_.Exception.Message })
                $ready = $false
            }
            if (-not $ready -and (Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 1
            }
        } while (-not $ready -and (Get-Date) -lt $deadline)

        $checks += [PSCustomObject]@{
            ingestionBaseUrl = $base
            status = if ($ready) { "READY" } else { "TIMEOUT" }
            healthStatus = $healthStatus
            readinessEndpoint = "/actuator/health/readiness"
            sampledSources = @($sourceStates)
        }
    }
    return @($checks)
}

function Invoke-CapacityFixtureCleanup {
    param(
        [Parameter(Mandatory = $true)] [string] $RunDirectory,
        [string] $DeviceApiBaseUrl = "http://localhost:8082",
        [string] $BearerToken = "",
        [switch] $DryRun,
        [int] $TimeoutSeconds = 30
    )

    $paths = Get-CapacityProvisioningPaths -RunDirectory $RunDirectory
    $state = Read-CapacityJsonFile -Path $paths.statePath -Default $null
    if ($null -eq $state) {
        throw "provisioning-state.json not found in run directory: $RunDirectory"
    }

    $deletedDataSources = 0
    $decommissionedDevices = 0
    foreach ($source in @($state.dataSources | Sort-Object -Property deviceIndex -Descending)) {
        if ($source.status -eq "DISABLED") {
            continue
        }
        if (-not $DryRun) {
            $headers = New-CapacityApiHeaders `
                -UserId $source.accountId `
                -Email "capacity-cleanup-$($source.tenantIndex)@load.local" `
                -BearerToken $BearerToken
            Invoke-CapacityApiJson `
                -Method "DELETE" `
                -BaseUrl $DeviceApiBaseUrl `
                -Path "/devices/$($source.apiDeviceId)/data-sources/$($source.apiDataSourceId)" `
                -Headers $headers `
                -TimeoutSeconds $TimeoutSeconds | Out-Null
        }
        $source.status = if ($DryRun) { "PLANNED_DISABLE" } else { "DISABLED" }
        $deletedDataSources += 1
    }

    foreach ($device in @($state.devices | Sort-Object -Property deviceIndex -Descending)) {
        if ($device.status -eq "DECOMMISSIONED") {
            continue
        }
        if (-not $DryRun) {
            $headers = New-CapacityApiHeaders `
                -UserId $device.accountId `
                -Email "capacity-cleanup-$($device.tenantIndex)@load.local" `
                -BearerToken $BearerToken
            Invoke-CapacityApiJson `
                -Method "POST" `
                -BaseUrl $DeviceApiBaseUrl `
                -Path "/devices/$($device.apiDeviceId)/decommission" `
                -Headers $headers `
                -TimeoutSeconds $TimeoutSeconds | Out-Null
        }
        $device.status = if ($DryRun) { "PLANNED_DECOMMISSION" } else { "DECOMMISSIONED" }
        $decommissionedDevices += 1
    }

    $state.cleanup = @{
        status = if ($DryRun) { "DRY_RUN" } else { "COMPLETED" }
        deletedDataSources = $deletedDataSources
        decommissionedDevices = $decommissionedDevices
        completedAt = (Get-Date).ToUniversalTime().ToString("o")
    }
    $state.status = if ($DryRun) { "DRY_RUN_CLEANUP" } else { "CLEANED_UP" }
    Write-CapacityJsonFile -Value $state -Path $paths.statePath

    $summary = [PSCustomObject]@{
        schemaVersion = 1
        runId = $state.runId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        status = $state.status
        cleanup = $state.cleanup
        statePath = "provisioning-state.json"
    }
    Write-CapacityJsonFile -Value $summary -Path $paths.summaryPath
    return [PSCustomObject]@{
        status = $state.status
        runDirectory = $RunDirectory
        statePath = $paths.statePath
        summaryPath = $paths.summaryPath
    }
}

function Test-CapacityProfileSupport {
    param(
        [Parameter(Mandatory = $true)] [object] $Document,
        [Parameter(Mandatory = $true)] [object] $Profile
    )

    $supportedMetrics = @($Document.supportedNumericMetrics)
    $metricNames = @($Profile.metricNames)
    $blockers = New-Object System.Collections.Generic.List[string]

    foreach ($metricName in $metricNames) {
        if ($supportedMetrics -notcontains $metricName) {
            $blockers.Add("Metric '$metricName' is not in the supported numeric catalog.")
        }
    }

    if ([int] $Profile.requiredNumericMetricCount -ne $metricNames.Count) {
        $blockers.Add("Profile requires $($Profile.requiredNumericMetricCount) numeric metrics but defines $($metricNames.Count) supported metric names.")
    }

    if ([int] $Profile.requiredNumericMetricCount -gt $supportedMetrics.Count) {
        $blockers.Add("Profile requires $($Profile.requiredNumericMetricCount) numeric metrics; current catalog exposes $($supportedMetrics.Count).")
    }

    return [PSCustomObject]@{
        status = if ($blockers.Count -eq 0) { "PASS" } else { "BLOCKED" }
        blockers = @($blockers)
        supportedMetricNames = $supportedMetrics
        profileMetricNames = $metricNames
    }
}

function New-CapacityPreflight {
    param(
        [Parameter(Mandatory = $true)] [string] $ProfileName,
        [Parameter(Mandatory = $true)] [string] $PhaseName,
        [string] $RunId = $null,
        [string] $ProfilePath = (Join-Path (Get-CapacityHarnessRoot) "profiles.json")
    )

    $document = Get-CapacityProfileDocument -ProfilePath $ProfilePath
    $profile = Get-NamedProperty -Object $document.profiles -Name $ProfileName -Kind "profile"
    $phase = Get-NamedProperty -Object $document.phases -Name $PhaseName -Kind "phase"
    if ($null -eq $RunId -or $RunId.Trim().Length -eq 0) {
        $RunId = "load-$((Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ"))-$ProfileName-$PhaseName"
    }

    $support = Test-CapacityProfileSupport -Document $document -Profile $profile
    $workload = Get-CapacityWorkloadArithmetic -Profile $profile -Phase $phase
    $verdicts = [ordered]@{
        profilePreflight = $support.status
        generation = "NOT_RUN"
        visibilityObservation = "NOT_RUN"
        persistenceReconciliation = "NOT_RUN"
        monthlyVolume = "NOT_RUN"
        scaling = "NOT_RUN"
        recovery = "NOT_RUN"
    }

    $warnings = New-Object System.Collections.Generic.List[string]
    if ($ProfileName -eq "current-5") {
        $warnings.Add("current-5 evidence cannot satisfy the resume-10 capacity claim.")
    }
    if ($PhaseName -ne "preflight") {
        $warnings.Add("Profile arithmetic passed; capacity claims still require generator execution, committed visibility and reconciliation evidence.")
    }

    return [PSCustomObject]@{
        schemaVersion = 1
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        profile = $ProfileName
        phase = $PhaseName
        status = $support.status
        description = $profile.description
        phaseDescription = $phase.description
        protocol = $profile.protocol
        seed = $profile.seed
        workload = $workload
        supportedMetricNames = $support.supportedMetricNames
        profileMetricNames = $support.profileMetricNames
        blockers = @($support.blockers)
        warnings = @($warnings)
        thresholds = $document.thresholds
        verdicts = [PSCustomObject]$verdicts
        requiredRunEvidence = @(
            "executed-generator-result",
            "accepted-response-to-committed-visibility-samples",
            "automatic-timescaledb-logical-key-reconciliation"
        )
        pendingCapabilities = @(
            "optional-full-monthly-volume-execution",
            "optional-scaling-execution",
            "optional-recovery-execution"
        )
    }
}

function Write-CapacityEvidence {
    param(
        [Parameter(Mandatory = $true)] [object] $Preflight,
        [string] $OutputRoot = (Join-Path (Resolve-Path (Join-Path (Get-CapacityHarnessRoot) "..\..")).Path "artifacts\load-tests")
    )

    $runDirectory = Join-Path $OutputRoot $Preflight.runId
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

    $manifestPath = Join-Path $runDirectory "manifest.json"
    $summaryPath = Join-Path $runDirectory "summary.json"
    $reportPath = Join-Path $runDirectory "report.md"

    $manifest = [PSCustomObject]@{
        schemaVersion = $Preflight.schemaVersion
        runId = $Preflight.runId
        generatedAt = $Preflight.generatedAt
        profile = $Preflight.profile
        phase = $Preflight.phase
        seed = $Preflight.seed
        workload = $Preflight.workload
        supportedMetricNames = $Preflight.supportedMetricNames
        profileMetricNames = $Preflight.profileMetricNames
        note = "Sanitized manifest only; source tokens, credentials and device secrets are intentionally excluded."
    }

    $summary = [PSCustomObject]@{
        schemaVersion = $Preflight.schemaVersion
        runId = $Preflight.runId
        generatedAt = $Preflight.generatedAt
        profile = $Preflight.profile
        phase = $Preflight.phase
        status = $Preflight.status
        workload = $Preflight.workload
        blockers = $Preflight.blockers
        warnings = $Preflight.warnings
        verdicts = $Preflight.verdicts
        requiredRunEvidence = $Preflight.requiredRunEvidence
        pendingCapabilities = $Preflight.pendingCapabilities
        thresholds = $Preflight.thresholds
    }

    $manifest | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $summary | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

    $report = @(
        "# VoltCanary Capacity Preflight",
        "",
        "- Run ID: $($Preflight.runId)",
        "- Profile: $($Preflight.profile)",
        "- Phase: $($Preflight.phase)",
        "- Status: $($Preflight.status)",
        "- Devices: $($Preflight.workload.deviceCount)",
        "- Measurement seconds: $($Preflight.workload.measurementSeconds)",
        "- Scheduled readings: $($Preflight.workload.scheduledReadings)",
        "- Expected numeric values: $($Preflight.workload.expectedNumericValues)",
        "- Estimated REST requests at batch size $($Preflight.workload.batchSize): $($Preflight.workload.estimatedRequests)",
        "",
        "## Verdicts",
        "",
        "| Claim | Verdict |",
        "| --- | --- |"
    )

    foreach ($property in $Preflight.verdicts.PSObject.Properties) {
        $report += "| $($property.Name) | $($property.Value) |"
    }

    if ($Preflight.blockers.Count -gt 0) {
        $report += @("", "## Blockers", "")
        foreach ($blocker in $Preflight.blockers) {
            $report += "- $blocker"
        }
    }

    if ($Preflight.warnings.Count -gt 0) {
        $report += @("", "## Warnings", "")
        foreach ($warning in $Preflight.warnings) {
            $report += "- $warning"
        }
    }

    $report += @(
        "",
        "## Required Run Evidence",
        ""
    )
    foreach ($evidenceItem in $Preflight.requiredRunEvidence) {
        $report += "- $evidenceItem"
    }
    $report += @(
        "",
        "## Optional Unexecuted Extensions",
        ""
    )
    foreach ($capability in $Preflight.pendingCapabilities) {
        $report += "- $capability"
    }

    $report | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Update-CapacityRunReport -RunDirectory $runDirectory | Out-Null

    return [PSCustomObject]@{
        runDirectory = $runDirectory
        manifestPath = $manifestPath
        summaryPath = $summaryPath
        reportPath = $reportPath
        status = $Preflight.status
    }
}

Export-ModuleMember -Function `
    Get-CapacityProfileDocument, `
    Get-CapacityWorkloadArithmetic, `
    Get-CapacityMetricValue, `
    Get-CapacityWorkerRanges, `
    New-CapacityEnvironmentManifest, `
    New-CapacityObservabilityInventory, `
    New-CapacityPreflight, `
    New-CapacityFixtureSchedule, `
    Test-CapacityProfileSupport, `
    Write-CapacityEvidence, `
    Write-CapacityObservabilityInventory, `
    Update-CapacityRunReport, `
    Write-CapacityEnvironmentManifest, `
    Write-CapacityFixtureSchedule, `
    New-CapacityGenerationPlan, `
    Write-CapacityGenerationPlan, `
    New-CapacityGenerationResult, `
    ConvertFrom-CapacityK6RestOutcomes, `
    ConvertFrom-CapacityK6VisibilityOutcomes, `
    Write-CapacityGenerationResult, `
    New-CapacityStorageFixturePlan, `
    Test-CapacityStorageFixtureCheckpoint, `
    Write-CapacityStorageFixturePlan, `
    New-CapacityMonthlyTrialPlan, `
    Write-CapacityMonthlyTrialPlan, `
    New-CapacityScalingTrialPlan, `
    Write-CapacityScalingTrialPlan, `
    New-CapacityRecoveryTrialPlan, `
    Write-CapacityRecoveryTrialPlan, `
    New-CapacityLogicalMeasurement, `
    New-CapacityVisibilityObservation, `
    Write-CapacityVisibilityObservation, `
    Invoke-CapacityVisibilityObservation, `
    Test-CapacityLogicalReconciliation, `
    Write-CapacityReconciliation, `
    ConvertFrom-CapacityCommittedMeasurementCsv, `
    Invoke-CapacityLogicalReconciliation, `
    Export-CapacityCommittedMeasurements, `
    Invoke-CapacityAutomaticReconciliation, `
    Invoke-CapacityProtocolGeneration, `
    Invoke-CapacityRunnerPhase, `
    Invoke-CapacityFixtureProvisioning, `
    Invoke-CapacityFixtureCleanup, `
    Wait-CapacitySourceRuntimeReadiness
