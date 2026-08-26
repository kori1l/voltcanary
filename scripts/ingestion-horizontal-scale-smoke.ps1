param(
    [int] $ReplicaAPort = 18083,
    [int] $ReplicaBPort = 18084,
    [int] $FixturePort = 18085,
    [int] $TimeoutSeconds = 420,
    [switch] $StartStack,
    [switch] $CiMode,
    [string] $SummaryPath = "artifacts/ingestion-horizontal-scale-summary.json"
)

$ErrorActionPreference = "Stop"
if ($CiMode) {
    $ProgressPreference = "SilentlyContinue"
}

$RunId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$CorrelationId = "ingestion-horizontal-scale-$RunId"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BackendDir = Join-Path $RepoRoot "backend"
$ArtifactDir = Join-Path $RepoRoot "artifacts"
$script:CurrentSmokeStep = "initializing"
$script:Processes = @{}
$script:FixtureJob = $null

function Write-Step {
    param([string] $Message)
    $script:CurrentSmokeStep = $Message
    Write-Host "[$(Get-Date -Format HH:mm:ss)] $Message"
}

function ConvertTo-BodyJson {
    param([object] $Value)
    return ($Value | ConvertTo-Json -Depth 40 -Compress)
}

function Invoke-Json {
    param(
        [Parameter(Mandatory = $true)] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Uri,
        [object] $Body = $null,
        [hashtable] $Headers = @{}
    )

    try {
        if ($null -ne $Body) {
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -Body (ConvertTo-BodyJson $Body) -ContentType "application/json"
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
    } catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $detail = "$detail $($_.ErrorDetails.Message)"
        }
        throw "$Method $Uri failed: $detail"
    }
}

function Wait-For {
    param(
        [Parameter(Mandatory = $true)] [string] $Description,
        [Parameter(Mandatory = $true)] [scriptblock] $Condition,
        [int] $Timeout = $TimeoutSeconds,
        [int] $DelaySeconds = 2
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

function New-Sha256Hex {
    param([string] $Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($bytes)
        return [System.BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Start-Replica {
    param([string] $Name, [int] $Port, [string] $InstanceId)

    $logPath = Join-Path $ArtifactDir "$CorrelationId-$Name.log"
    $command = @"
Set-Location '$BackendDir'
`$env:SPRING_PROFILES_ACTIVE='local'
`$env:SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
`$env:SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET='earliest'
`$env:SERVER_PORT='$Port'
`$env:REDIS_HOST='localhost'
`$env:REDIS_PORT='6379'
`$env:APP_KAFKA_PUBLISH_ENABLED='true'
`$env:INGESTION_IDEMPOTENCY_REDIS_ENABLED='true'
`$env:INGESTION_RATE_LIMIT_REDIS_ENABLED='true'
`$env:INGESTION_MULTI_REPLICA_MODE='true'
`$env:INGESTION_LOCAL_CORRECTNESS_STATE_ENABLED='false'
`$env:APP_INGESTION_HORIZONTAL_SCALE_SMOKE_ENABLED='true'
`$env:APP_INGESTION_CONNECTOR_RUNTIME_INSTANCE_ID='$InstanceId'
`$env:INGESTION_CONNECTOR_RUNTIME_TICK_INTERVAL='1s'
`$env:INGESTION_CONNECTOR_RUNTIME_DEFAULT_POLLING_INTERVAL='2s'
`$env:INGESTION_CONNECTOR_RUNTIME_LEASE_TTL='4s'
`$env:INGESTION_SOURCE_REQUESTS_PER_WINDOW='2'
`$env:INGESTION_DEVICE_REQUESTS_PER_WINDOW='100'
`$env:INGESTION_ACCOUNT_REQUESTS_PER_WINDOW='100'
`$env:INGESTION_IP_REQUESTS_PER_WINDOW='100'
mvn -q -f services/ingestion-service/pom.xml spring-boot:run
"@
    $process = Start-Process -FilePath "powershell" `
        -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command `
        -RedirectStandardOutput $logPath `
        -RedirectStandardError ($logPath -replace "\.log$", ".err.log") `
        -WindowStyle Hidden `
        -PassThru
    $script:Processes[$Name] = [PSCustomObject]@{
        Process = $process
        Port = $Port
        InstanceId = $InstanceId
        LogPath = $logPath
    }
}

function Stop-ProcessTree {
    param([int] $RootProcessId)
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -RootProcessId $child.ProcessId
    }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

function Stop-Replica {
    param([string] $Name)
    if ($script:Processes.ContainsKey($Name)) {
        $process = $script:Processes[$Name].Process
        if ($process -and -not $process.HasExited) {
            Stop-ProcessTree -RootProcessId $process.Id
        }
        $script:Processes.Remove($Name)
    }
}

function Stop-PortListeners {
    param([int[]] $Ports)
    $listenerIds = Get-NetTCPConnection -LocalPort $Ports -ErrorAction SilentlyContinue |
        Where-Object { $_.State -eq "Listen" -and $_.OwningProcess -ne 0 } |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processId in $listenerIds) {
        Stop-ProcessTree -RootProcessId $processId
    }
}

function Start-Fixture {
    $script:FixtureJob = Start-Job -ArgumentList $FixturePort -ScriptBlock {
        param([int] $Port)
        $listener = [System.Net.HttpListener]::new()
        $listener.Prefixes.Add("http://127.0.0.1:$Port/")
        $listener.Start()
        try {
            while ($listener.IsListening) {
                $context = $listener.GetContext()
                $body = @{
                    timestamp = (Get-Date).ToUniversalTime().ToString("o")
                    metrics = @{
                        activePowerW = 841.2
                        voltageV = 230.4
                    }
                } | ConvertTo-Json -Compress
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                $context.Response.StatusCode = 200
                $context.Response.ContentType = "application/json"
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                $context.Response.Close()
            }
        } finally {
            $listener.Stop()
        }
    }
}

function Get-ReplicaState {
    param([int] $Port, [guid] $DataSourceId)
    return Invoke-Json -Method GET -Uri "http://localhost:$Port/internal/ingestion/horizontal-scale-smoke/sources/$DataSourceId"
}

function Publish-SourceConfig {
    param([int] $Port, [object] $Body)
    return Invoke-Json -Method POST -Uri "http://localhost:$Port/internal/ingestion/horizontal-scale-smoke/source-runtime-configs" -Body $Body
}

function Wait-ForKnownSource {
    param([guid] $DataSourceId)
    return Wait-For "source runtime config on both replicas" {
        $a = Get-ReplicaState -Port $ReplicaAPort -DataSourceId $DataSourceId
        $b = Get-ReplicaState -Port $ReplicaBPort -DataSourceId $DataSourceId
        if ($a.knownRuntimeConfig -and $b.knownRuntimeConfig) {
            return @($a, $b)
        }
        return $false
    }
}

function Get-ConnectorOwner {
    param([guid] $DataSourceId, [int[]] $Ports)
    return Wait-For "exactly one connector owner" {
        $states = @()
        foreach ($port in $Ports) {
            $states += Get-ReplicaState -Port $port -DataSourceId $DataSourceId
        }
        $owners = @($states | Where-Object { $_.ownsConnectorLease -eq $true })
        if ($owners.Count -eq 1) {
            return $owners[0]
        }
        return $false
    }
}

function Read-RawTelemetryRecords {
    docker compose exec -T redpanda rpk topic consume telemetry.raw.v1 -n 200 -f '%k %v\n' --offset '@-2m:end' 2>$null
}

function Write-Summary {
    param([string] $Status, [hashtable] $Fields)
    $summary = [ordered]@{
        status = $Status
        runId = $RunId
        failedStep = $(if ($Status -eq "PASSED") { "" } else { $script:CurrentSmokeStep })
    }
    foreach ($key in $Fields.Keys) {
        $summary[$key] = $Fields[$key]
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent (Join-Path $RepoRoot $SummaryPath)) | Out-Null
    $summary | ConvertTo-Json -Depth 12 | Set-Content -Path (Join-Path $RepoRoot $SummaryPath) -Encoding UTF8
}

trap {
    Write-Summary -Status "FAILED" -Fields @{ error = "$($_.Exception.Message)" }
    break
}

try {
    New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null
    Stop-PortListeners -Ports @($ReplicaAPort, $ReplicaBPort, $FixturePort)

    if ($StartStack) {
        Write-Step "Starting shared Redis and Redpanda"
        docker compose --profile infra up -d redis redpanda redpanda-init
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose infra startup failed"
        }
    }

    Write-Step "Building ingestion-service"
    Push-Location $BackendDir
    mvn -q -pl services/ingestion-service -am -DskipTests install
    if ($LASTEXITCODE -ne 0) {
        throw "ingestion-service install failed"
    }
    Pop-Location

    Write-Step "Starting polling fixture and two ingestion replicas"
    Start-Fixture
    Start-Replica -Name "replica-a" -Port $ReplicaAPort -InstanceId "scale-smoke-a-$RunId"
    Start-Replica -Name "replica-b" -Port $ReplicaBPort -InstanceId "scale-smoke-b-$RunId"

    Wait-For "replica A health" {
        $health = Invoke-Json -Method GET -Uri "http://localhost:$ReplicaAPort/actuator/health"
        if ($health.status -eq "UP") { return $health }
        return $false
    } | Out-Null
    Wait-For "replica B health" {
        $health = Invoke-Json -Method GET -Uri "http://localhost:$ReplicaBPort/actuator/health"
        if ($health.status -eq "UP") { return $health }
        return $false
    } | Out-Null

    $accountId = [Guid]::NewGuid()
    $pushDeviceId = [Guid]::NewGuid()
    $pushDataSourceId = [Guid]::NewGuid()
    $connectorDeviceId = [Guid]::NewGuid()
    $connectorDataSourceId = [Guid]::NewGuid()
    $token = "vcsrc$RunId"
    $tokenHash = New-Sha256Hex -Value $token

    Write-Step "Publishing source runtime config"
    $pushSource = @{
        sourceId = "scale-push-$RunId"
        dataSourceId = $pushDataSourceId
        accountId = $accountId
        deviceId = $pushDeviceId
        adapterType = "GENERIC_HTTP_PUSH"
        protocol = "REST"
        transport = "HTTP"
        status = "ACTIVE"
        mappingSchemaVersion = "v1"
        adapterConfigSchemaVersion = "v1"
        mapping = @{}
        adapterConfig = @{}
        credentialReferences = @{}
        tokenPrefix = $token
        tokenHash = $tokenHash
        homeRegion = "local"
        configVersion = 1
        correlationId = $CorrelationId
    }
    $connectorSource = @{
        sourceId = "scale-connector-$RunId"
        dataSourceId = $connectorDataSourceId
        accountId = $accountId
        deviceId = $connectorDeviceId
        adapterType = "GENERIC_HTTP_POLLING"
        protocol = "HTTP"
        transport = "HTTP"
        status = "ACTIVE"
        mappingSchemaVersion = "v1"
        adapterConfigSchemaVersion = "v1"
        mapping = @{}
        adapterConfig = @{
            endpoint = "http://127.0.0.1:$FixturePort/reading"
            pollInterval = "1s"
        }
        credentialReferences = @{}
        tokenPrefix = ""
        tokenHash = ""
        homeRegion = "local"
        configVersion = 1
        correlationId = $CorrelationId
    }
    Publish-SourceConfig -Port $ReplicaAPort -Body $pushSource | Out-Null
    Publish-SourceConfig -Port $ReplicaAPort -Body $connectorSource | Out-Null
    Wait-ForKnownSource -DataSourceId $pushDataSourceId | Out-Null
    Wait-ForKnownSource -DataSourceId $connectorDataSourceId | Out-Null

    Write-Step "Verifying HTTP push, cross-replica duplicate detection, and shared rate limits"
    $timestamp = (Get-Date).ToUniversalTime().AddSeconds(-30).ToString("o")
    $headers = @{ Authorization = "Bearer $token"; "X-Correlation-Id" = $CorrelationId }
    $reading = @{
        deviceId = $pushDeviceId
        sourceId = "scale-push-$RunId"
        timestamp = $timestamp
        metrics = @{ activePowerW = 840.2; voltageV = 230.1 }
    }
    $accepted = Invoke-Json -Method POST -Uri "http://localhost:$ReplicaAPort/ingestion/readings" -Headers $headers -Body $reading
    Require-True ($accepted.acceptedCount -eq 1) "first replica did not accept push telemetry"
    $duplicate = Invoke-Json -Method POST -Uri "http://localhost:$ReplicaBPort/ingestion/readings" -Headers $headers -Body $reading
    Require-True ($duplicate.duplicateCount -eq 1) "duplicate sent to another replica was not rejected once"
    $limitedReading = @{
        deviceId = $pushDeviceId
        sourceId = "scale-push-$RunId"
        timestamp = (Get-Date).ToUniversalTime().AddSeconds(-20).ToString("o")
        metrics = @{ activePowerW = 841.2 }
    }
    $limited = $false
    try {
        Invoke-Json -Method POST -Uri "http://localhost:$ReplicaBPort/ingestion/readings" -Headers $headers -Body $limitedReading | Out-Null
    } catch {
        $limited = "$($_.Exception.Message)" -like "*429*" -or "$($_.Exception.Message)" -like "*TOO_MANY_REQUESTS*"
    }
    Require-True $limited "rate limit was not shared across replicas"

    Write-Step "Verifying deterministic Kafka key"
    $records = @(Read-RawTelemetryRecords)
    $matching = $records | Where-Object { $_ -like "$pushDeviceId *" } | Select-Object -First 1
    Require-True ($null -ne $matching) "telemetry.raw.v1 did not include a record keyed by deviceId $pushDeviceId"

    Write-Step "Verifying connector lease ownership and failover"
    $owner = Get-ConnectorOwner -DataSourceId $connectorDataSourceId -Ports @($ReplicaAPort, $ReplicaBPort)
    $ownerName = if ($owner.instanceId -like "*-a-*") { "replica-a" } else { "replica-b" }
    $survivorPort = if ($ownerName -eq "replica-a") { $ReplicaBPort } else { $ReplicaAPort }
    Stop-Replica -Name $ownerName
    $newOwner = Wait-For "connector ownership failover" {
        $state = Get-ReplicaState -Port $survivorPort -DataSourceId $connectorDataSourceId
        if ($state.ownsConnectorLease -eq $true) { return $state }
        return $false
    } -Timeout 60

    Write-Step "Verifying runtime config replay after restart"
    if ($ownerName -eq "replica-a") {
        Start-Replica -Name "replica-a" -Port $ReplicaAPort -InstanceId "scale-smoke-a-restarted-$RunId"
        $restartPort = $ReplicaAPort
    } else {
        Start-Replica -Name "replica-b" -Port $ReplicaBPort -InstanceId "scale-smoke-b-restarted-$RunId"
        $restartPort = $ReplicaBPort
    }
    Wait-For "restarted replica health" {
        $health = Invoke-Json -Method GET -Uri "http://localhost:$restartPort/actuator/health"
        if ($health.status -eq "UP") { return $health }
        return $false
    } | Out-Null
    Wait-For "restarted replica runtime config replay" {
        $state = Get-ReplicaState -Port $restartPort -DataSourceId $pushDataSourceId
        if ($state.knownRuntimeConfig -eq $true) { return $state }
        return $false
    } | Out-Null

    Write-Summary -Status "PASSED" -Fields @{
        pushDataSourceId = "$pushDataSourceId"
        connectorDataSourceId = "$connectorDataSourceId"
        firstConnectorOwner = $owner.instanceId
        failoverOwner = $newOwner.instanceId
        kafkaKey = "$pushDeviceId"
    }
    Write-Step "Horizontal ingestion smoke passed"
} finally {
    Stop-Replica -Name "replica-a"
    Stop-Replica -Name "replica-b"
    if ($script:FixtureJob) {
        Stop-Job -Job $script:FixtureJob -ErrorAction SilentlyContinue
        Remove-Job -Job $script:FixtureJob -Force -ErrorAction SilentlyContinue
    }
}
