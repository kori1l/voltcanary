param(
    [switch]$StartStack,
    [switch]$Build,
    [switch]$KeepRunning,
    [switch]$DockerServices,
    [switch]$UseRunning,
    [switch]$Stop,
    [string]$SummaryPath = "artifacts/device-variables-smoke-summary.json"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$artifactRoot = Join-Path $root "artifacts"
$composeFile = Join-Path $root "deploy/docker-compose/device-variables-smoke.yml"
$pidFile = Join-Path $artifactRoot "device-variable-smoke-processes.json"
$ownedProcesses = @()
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

function Stop-SmokeProcesses {
    if (-not (Test-Path -LiteralPath $pidFile)) { return }
    foreach ($entry in (Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json)) {
        if ($entry.ProcessId -lt 1) { continue }
        $process = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $entry.ProcessId) -ErrorAction SilentlyContinue
        if ($process -and $process.CommandLine.Contains($root) -and $process.CommandLine.Contains($entry.MainClass)) {
            Stop-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        }
    }
}
if ($Stop) {
    Stop-SmokeProcesses
    & docker compose -f $composeFile --profile applications down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw "Fixture cleanup failed" }
    return
}
function Require-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Api([string]$Method, [int]$Port, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $arguments = @{Method=$Method; Uri=("http://127.0.0.1:" + $Port + $Path); Headers=$Headers; TimeoutSec=10}
    if ($null -ne $Body) { $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 30 -Compress; $arguments.ContentType = "application/json" }
    Invoke-RestMethod @arguments
}
function Await-Result([scriptblock]$Read, [scriptblock]$Accepted, [string]$Description, [int]$Seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try { $value = & $Read; if (& $Accepted $value) { return $value } } catch { $lastFailure = $_.Exception.Message }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ("Timed out waiting for " + $Description + ". " + $lastFailure)
}
function Start-ServiceProcess([string]$Service, [string]$MainClass, [int]$Port, [string[]]$Options) {
    if ($DockerServices) {
        $libraryPath = Join-Path $root ("backend/services/" + $Service + "/target/variable-smoke-lib")
        Push-Location (Join-Path $root "backend")
        try {
            $ErrorActionPreference = "Continue"
            & mvn.cmd -q -pl ("services/" + $Service) dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$libraryPath" *> (Join-Path $artifactRoot ($Service + "-classpath.log"))
            $ErrorActionPreference = "Stop"
            if ($LASTEXITCODE -ne 0) { throw "Could not prepare Docker runtime for $Service" }
        } finally { Pop-Location }
        $runtimeOptions = @($Options | ForEach-Object { $_.Replace("localhost:15432", "postgres:5432").Replace("localhost:15433", "timescale:5432").Replace("localhost:18082", "device-service:18082").Replace("localhost:18084", "telemetry-service:18084").Replace("localhost:18085", "analytics-service:18085").Replace("localhost:18086", "alert-service:18086") })
        $javaArguments = @("java", "-Xmx384m", "-cp", "/workspace/backend/services/$Service/target/classes:/workspace/backend/services/$Service/target/variable-smoke-lib/*", $MainClass,
            "--server.port=$Port", "--spring.kafka.bootstrap-servers=redpanda:9093", "--app.internal.service-token=variable-smoke-internal",
            "--spring.jpa.hibernate.ddl-auto=none", "--management.health.redis.enabled=false") + $runtimeOptions
        & docker compose -f $composeFile run -d --service-ports --use-aliases --name ("voltcanary-variable-smoke-" + $Service) $Service @javaArguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not launch Docker $Service" }
        $script:ownedProcesses += [pscustomobject]@{ProcessId=0;MainClass=$MainClass;Service=$Service;Port=$Port}
        ConvertTo-Json -InputObject @($script:ownedProcesses) | Set-Content -LiteralPath $pidFile
        return
    }
    $classPathFile = Join-Path $root ("backend/services/" + $Service + "/target/variable-runtime-classpath.txt")
    Push-Location (Join-Path $root "backend")
    try {
        $ErrorActionPreference = "Continue"
        & mvn.cmd -q -pl ("services/" + $Service) dependency:build-classpath "-DincludeScope=runtime" "-Dmdep.outputFile=$classPathFile" *> (Join-Path $artifactRoot ($Service + "-classpath.log"))
        $ErrorActionPreference = "Stop"
        if ($LASTEXITCODE -ne 0) { throw "Could not build runtime classpath for $Service" }
    } finally { Pop-Location }
    $classPath = (Join-Path $root ("backend/services/" + $Service + "/target/classes")) + ";" + (Get-Content -LiteralPath $classPathFile -Raw).Trim()
    $arguments = @("-Xmx384m", "-cp", ('"' + $classPath + '"'), $MainClass,
        "--server.port=$Port", "--spring.kafka.bootstrap-servers=localhost:18092",
        "--app.internal.service-token=variable-smoke-internal", "--spring.jpa.hibernate.ddl-auto=none",
        "--app.jwt.public-keys=", "--management.health.redis.enabled=false") + $Options
    $process = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $arguments -PassThru -WindowStyle Hidden -WorkingDirectory $root         -RedirectStandardOutput (Join-Path $artifactRoot ($Service + "-variable-smoke.log"))         -RedirectStandardError (Join-Path $artifactRoot ($Service + "-variable-smoke.err.log"))
    $script:ownedProcesses += [pscustomobject]@{ProcessId=$process.Id;MainClass=$MainClass;Service=$Service;Port=$Port}
    ConvertTo-Json -InputObject @($script:ownedProcesses) | Set-Content -LiteralPath $pidFile
}
try {
    if ($UseRunning) {
        $ownedProcesses = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
    } else {
    if ($StartStack) {
        & docker compose -f $composeFile up -d --wait
        if ($LASTEXITCODE -ne 0) { throw "Fixture infrastructure failed" }
    }
    foreach ($database in @("analytics", "alerts", "identity", "dashboard")) {
        $exists = & docker compose -f $composeFile exec -T postgres psql -U variable -d device -tAc ("select 1 from pg_database where datname='" + $database + "'")
        if ("$exists" -notmatch '\b1\b') {
            & docker compose -f $composeFile exec -T postgres psql -U variable -d device -c ("CREATE DATABASE " + $database) | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not initialize $database" }
        }
    }
    foreach ($topic in @("source.runtime-config.v1","source.binding-schema.v1","source.processing-config.v1","telemetry.raw.v1","telemetry.validated.v1",
            "telemetry.measurements.accepted.v1","anomaly.detected.v1","risk-score.changed.v1","device-registry.changed.v1",
            "source.processing-config.dlq.v1","analytics.variable-measurements.dlq.v1")) {
        $topicArguments = @("compose","-f",$composeFile,"exec","-T","redpanda","rpk","topic","create",$topic,"-X","brokers=localhost:9093","-p","3","-r","1")
        if ($topic -in @("source.runtime-config.v1","source.binding-schema.v1","source.processing-config.v1")) { $topicArguments += @("-c","cleanup.policy=compact") }
        & docker @topicArguments *> (Join-Path $artifactRoot "variable-smoke-topics.log")
    }
    if ($Build) {
        Write-Output "Building variable service runtime"
        Push-Location (Join-Path $root "backend")
        try {
            $ErrorActionPreference = "Continue"
            & mvn.cmd -q -pl services/api-gateway,services/identity-service,services/device-service,services/ingestion-service,services/telemetry-service,services/analytics-service,services/alert-service,services/dashboard-service -am "-DskipTests" "-Dmaven.compiler.useIncrementalCompilation=false" install *> (Join-Path $artifactRoot "variable-smoke-build.log")
            $ErrorActionPreference = "Stop"
            if ($LASTEXITCODE -ne 0) { throw "Variable service build failed" }
        } finally { Pop-Location }
    }
    foreach ($port in @(18082,18083,18084,18085,18086)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Fixture port $port is already in use; stop the previous owned fixture first." }
    }
    $databaseOptions = @("--spring.datasource.username=variable","--spring.datasource.password=variable-local-test","--spring.flyway.enabled=true")
    Start-ServiceProcess "identity-service" "com.voltcanary.identity.IdentityServiceApplication" 18081 ($databaseOptions + @("--spring.datasource.url=jdbc:postgresql://localhost:15432/identity"))
    if ($DockerServices) {
        Start-ServiceProcess "api-gateway" "com.voltcanary.gateway.ApiGatewayApplication" 18080 @("--app.gateway.rate-limit.enabled=false", "--app.gateway.cors.allowed-origin-patterns[0]=http://localhost:13000")
        Start-ServiceProcess "dashboard-service" "com.voltcanary.dashboard.DashboardServiceApplication" 18089 ($databaseOptions + @("--spring.datasource.url=jdbc:postgresql://localhost:15432/dashboard"))
    }
    Start-ServiceProcess "device-service" "com.voltcanary.device.DeviceServiceApplication" 18082 ($databaseOptions + @(
        "--spring.datasource.url=jdbc:postgresql://localhost:15432/device", "--app.kafka.publish-enabled=true",
        "--app.variables.activation-enabled=true"))
    Start-ServiceProcess "ingestion-service" "com.voltcanary.ingestion.IngestionServiceApplication" 18083 @(
        "--app.device-service.base-url=http://localhost:18082", "--app.ingestion.kafka-publish-enabled=true",
        "--app.ingestion.redis-rate-limit-enabled=false","--app.idempotency.redis-enabled=false",
        "--app.ingestion.multi-replica-mode=false",
        "--app.ingestion.local-correctness-state-enabled=true","--app.ingestion.connector-runtime.enabled=false",
        "--app.variables.enabled=true","--app.variables.consume-enabled=true",
        "--app.variables.device-service-base-url=http://localhost:18082","--app.variables.internal-service-token=variable-smoke-internal")
    Start-ServiceProcess "telemetry-service" "com.voltcanary.telemetry.TelemetryServiceApplication" 18084 ($databaseOptions + @(
        "--spring.datasource.url=jdbc:postgresql://localhost:15433/telemetry","--app.kafka.consume-enabled=true","--app.kafka.publish-enabled=true",
        "--app.telemetry.validation.redis-rate-limit-enabled=false","--app.telemetry.authorization.device-service-base-url=http://localhost:18082",
        "--app.telemetry.authorization.internal-service-token=variable-smoke-internal","--app.variables.enabled=true",
        "--app.variables.consume-enabled=true","--app.variables.device-service-base-url=http://localhost:18082",
        "--app.variables.internal-service-token=variable-smoke-internal"))
    Start-ServiceProcess "analytics-service" "com.voltcanary.analytics.AnalyticsServiceApplication" 18085 ($databaseOptions + @(
        "--spring.datasource.url=jdbc:postgresql://localhost:15432/analytics","--app.kafka.consume-enabled=true","--app.kafka.publish-enabled=true",
        "--app.variables.consume-enabled=true","--app.telemetry-service.base-url=http://localhost:18084",
        "--app.device-service.base-url=http://localhost:18082","--app.alert-service.base-url=http://localhost:18086",
        "--app.analytics.authorization.device-service-base-url=http://localhost:18082","--app.analytics.outbox.reconciliation.enabled=false"))
    Start-ServiceProcess "alert-service" "com.voltcanary.alert.AlertServiceApplication" 18086 ($databaseOptions + @(
        "--spring.datasource.url=jdbc:postgresql://localhost:15432/alerts","--app.kafka.consume-enabled=true",
        "--app.alert.authorization.device-service-base-url=http://localhost:18082",
        "--app.variables.telemetry-service-base-url=http://localhost:18084","--app.analytics-service.base-url=http://localhost:18085",
        "--app.device-service.base-url=http://localhost:18082"))
    }
    Write-Output "Waiting for isolated services"
    foreach ($entry in $ownedProcesses) {
        $health = Await-Result { Api "GET" $entry.Port "/actuator/health" } {param($value) $value.status -eq "UP"} $entry.Service 120
    }
    $email = "variable-" + [guid]::NewGuid().ToString() + "@example.test"
    $registration = Api "POST" 18081 "/auth/register" @{email=$email;password="Variable-smoke-2026!"}
    $owner = $registration.userId
    $session = Api "POST" 18081 "/auth/login" @{email=$email;password="Variable-smoke-2026!"}
    $headers = @{Authorization=("Bearer " + $session.accessToken)}
    $account = Api "GET" 18082 "/accounts/current" $null $headers
    $devices = @(); $sources = @(); $credentials = @()
    for ($index=0; $index -lt 2; $index++) {
        $device = Api "POST" 18082 "/devices" @{name="Variable fixture $index";type="FRIDGE"} $headers
        $field = @{path="sensorTemperatureRaw";key="sensorTemperatureRaw";label="Raw temperature";type="number";unit="C";store=$true;display=$true}
        $source = Api "POST" 18082 ("/devices/" + $device.id + "/data-sources") @{sourceType="SENSOR";protocol="REST";externalId=("variable-" + [guid]::NewGuid());mapping=@{telemetryPolicy=@{fields=@($field)}}} $headers
        $credential = Api "POST" 18082 ("/devices/" + $device.id + "/data-sources/" + $source.id + "/token") @{} $headers
        $devices += $device; $sources += $source; $credentials += $credential.token
    }
    $inputNode = @{op="INPUT";alias="x"}
    $expression = @{op="SUBTRACT";left=@{op="MULTIPLY";left=$inputNode;right=@{op="CONSTANT";value=0.1}};right=@{op="CONSTANT";value=0.8}}
    $draft = @{key="chamberTemperature";label="Chamber temperature";origin="DERIVED";unit="C";inputs=@{x=@{unit="C"}};expression=$expression}
    $detail = Api "POST" 18082 "/device-types/FRIDGE/variables" $draft $headers
    $variableId = $detail.definition.id
    $preview = Api "POST" 18084 "/telemetry/variables/preview" @{deviceId=$devices[0].id;dataSourceId=$sources[0].id;contract=$detail.revisions[0].contract;inputs=@{x=235}} $headers
    Require-True ([Math]::Abs($preview.value - 22.7) -lt 0.000001) "Preview conversion failed"
    $detail = Api "POST" 18082 ("/device-types/FRIDGE/variables/" + $variableId + "/publish") @{expectedVersion=1;revision=1;makeDefault=$false} $headers
    for ($index=0; $index -lt 2; $index++) {
        $binding = Api "PUT" 18082 ("/devices/" + $devices[$index].id + "/data-sources/" + $sources[$index].id + "/variable-bindings") @{variableId=$variableId;revision=1;expectedVersion=0;inputPaths=@{x="sensorTemperatureRaw"};enabled=$true} $headers
        $rawValue = if ($index -eq 0) {235} else {185}
        $reading = @{deviceId=$devices[$index].id;sourceId=$sources[$index].externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=$rawValue}}
        $sourceHeaders = @{Authorization=("Bearer " + $credentials[$index])}
        $expectedValue = if ($index -eq 0) {22.7} else {17.7}
        $activationPath = "/telemetry/devices/" + $devices[$index].id + "/latest?variableId=$variableId&sourceId=" + $sources[$index].id
        $accepted = Await-Result {
            $reading.timestamp = [DateTime]::UtcNow.ToString("o")
            $null = Api "POST" 18083 "/ingestion/readings" $reading $sourceHeaders
            Api "GET" 18084 $activationPath $null $headers
        } {param($value) $value.points.Count -eq 1 -and [Math]::Abs($value.points[0].value - $expectedValue) -lt 0.000001} "source activation and first materialized result"
    }
    $latestPath = "/telemetry/devices/" + $devices[0].id + "/latest?variableId=$variableId&sourceId=" + $sources[0].id
    $latest = Await-Result {Api "GET" 18084 $latestPath $null $headers} {param($value) $value.points.Count -eq 1 -and [Math]::Abs($value.points[0].value - 22.7) -lt 0.000001} "stored derived value"
    $secondPath = "/telemetry/devices/" + $devices[1].id + "/latest?variableId=$variableId&sourceId=" + $sources[1].id
    $secondLatest = Await-Result {Api "GET" 18084 $secondPath $null $headers} {param($value) $value.points.Count -eq 1 -and [Math]::Abs($value.points[0].value - 17.7) -lt 0.000001} "independent second source value"
    $ruleHeaders = $headers
    $rule = Api "POST" 18086 "/alert-rules" @{name="Variable fixture threshold";ruleType="THRESHOLD";enabled=$true;scopeType="DEVICE";scopeId=$devices[0].id;metric=$latest.metric;conditionType="ABOVE_THRESHOLD";threshold=24;thresholdUnit="C";severity="WARNING";config=@{variable=@{variableId=$variableId;dataSourceId=$sources[0].id;unit="C";maxLiveAgeSeconds=600}}} $ruleHeaders
    $spike = @{deviceId=$devices[0].id;sourceId=$sources[0].externalId;timestamp=[DateTime]::UtcNow.ToString("o");metrics=@{sensorTemperatureRaw=255}}
    $sourceHeaders = @{Authorization=("Bearer " + $credentials[0])}
    $accepted = Api "POST" 18083 "/ingestion/readings" $spike $sourceHeaders
    $alerts = Await-Result {Api "GET" 18086 ("/alerts?deviceId=" + $devices[0].id) $null $headers} {param($value) @($value).Count -gt 0} "variable threshold alert" 120
    $duplicate = Api "POST" 18083 "/ingestion/readings" $spike $sourceHeaders
    Require-True ($duplicate.duplicateCount -eq 1) "Input replay was not deduplicated"
    $history = Api "GET" 18084 ("/telemetry/devices/" + $devices[0].id + "/readings?variableId=$variableId&sourceId=" + $sources[0].id + "&from=" + [uri]::EscapeDataString([DateTime]::UtcNow.AddMinutes(-10).ToString("o")) + "&to=" + [uri]::EscapeDataString([DateTime]::UtcNow.AddMinutes(1).ToString("o")) + "&bucketSeconds=60") $null $headers
    Require-True ($history.points.Count -gt 0) "Variable graph query returned no points"
    if ($DockerServices) {
        $gatewayVariables = Api "GET" 18080 ("/telemetry/devices/" + $devices[0].id + "/variables") $null @{Authorization=("Bearer " + $session.accessToken)}
        Require-True (@($gatewayVariables).Count -gt 0) "Authenticated gateway variable descriptors failed"
        $previousApiBase = $env:VITE_API_BASE_URL
        try {
            $env:VITE_API_BASE_URL = "http://localhost:18080"
            Push-Location (Join-Path $root "frontend")
            $ErrorActionPreference = "Continue"
            & npm.cmd run build *> (Join-Path $artifactRoot "variable-docker-frontend-build.log")
            $ErrorActionPreference = "Stop"
            if ($LASTEXITCODE -ne 0) { throw "Docker console build failed" }
        } finally { Pop-Location; $env:VITE_API_BASE_URL = $previousApiBase }
        & docker compose -f $composeFile --profile applications up -d frontend
        if ($LASTEXITCODE -ne 0) { throw "Docker console startup failed" }
    }
    $summary = [ordered]@{status="passed";dockerServices=[bool]$DockerServices;fixture="isolated real PostgreSQL/Timescale/Redpanda";ownerId=$owner;email=$email;accountId=$account.id;deviceIds=@($devices.id);sourceIds=@($sources.id);variableId=$variableId;preview=$preview.value;stored=$latest.points[0].value;secondStored=$secondLatest.points[0].value;ruleId=$rule.id;alertCount=@($alerts).Count;duplicateCount=$duplicate.duplicateCount;recordedAt=[DateTime]::UtcNow.ToString("o")}
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $root $SummaryPath)
    Write-Output "Conversion, shared type binding, telemetry, graph, threshold alert and duplicate acceptance passed."
} finally {
    if ($DockerServices) {
        $ErrorActionPreference = "Continue"
        foreach ($entry in $ownedProcesses) {
            & docker logs ("voltcanary-variable-smoke-" + $entry.Service) *> (Join-Path $artifactRoot ($entry.Service + "-variable-docker.log"))
        }
        $ErrorActionPreference = "Stop"
    }
    if (-not $KeepRunning) {
        Stop-SmokeProcesses
        if ($StartStack) { & docker compose -f $composeFile --profile applications down --volumes --remove-orphans | Out-Null }
    }
}
