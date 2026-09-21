param(
    [Parameter(Mandatory = $true)]
    [string] $RunDirectory,

    [int] $WorkerIndex = 0,
    [string] $BrokerHost = "localhost",
    [int] $BrokerPort = 1883,
    [ValidateRange(0, 1)]
    [int] $Qos = 1,
    [int] $ConnectionCount = 1,
    [switch] $DryRun,
    [switch] $DisableScheduleWait,
    [int] $MaxDurationSeconds = 0,
    [int] $ConnectTimeoutMillis = 5000,
    [int] $AckTimeoutMillis = 5000
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "CapacityHarness.psm1") -Force

if ($ConnectionCount -lt 1) {
    throw "ConnectionCount must be positive."
}

function ConvertTo-MqttUtf8 {
    param([Parameter(Mandatory = $true)] [string] $Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    if ($bytes.Length -gt 65535) {
        throw "MQTT UTF-8 field is too large."
    }
    return [byte[]] @([byte] (($bytes.Length -shr 8) -band 0xff), [byte] ($bytes.Length -band 0xff)) + $bytes
}

function ConvertTo-MqttRemainingLength {
    param([Parameter(Mandatory = $true)] [int] $Length)
    $encoded = New-Object System.Collections.Generic.List[byte]
    do {
        $digit = $Length % 128
        $Length = [math]::Floor($Length / 128)
        if ($Length -gt 0) {
            $digit = $digit -bor 128
        }
        $encoded.Add([byte] $digit)
    } while ($Length -gt 0)
    return [byte[]] $encoded.ToArray()
}

function Send-MqttPacket {
    param(
        [Parameter(Mandatory = $true)] [System.Net.Sockets.NetworkStream] $Stream,
        [Parameter(Mandatory = $true)] [byte] $PacketType,
        [Parameter(Mandatory = $true)] [byte[]] $Body
    )
    $fixedHeader = [byte[]] @($PacketType) + (ConvertTo-MqttRemainingLength -Length $Body.Length)
    $packet = [byte[]] $fixedHeader + $Body
    $Stream.Write($packet, 0, $packet.Length)
}

function Read-MqttPacket {
    param([Parameter(Mandatory = $true)] [System.Net.Sockets.NetworkStream] $Stream)
    $first = $Stream.ReadByte()
    if ($first -lt 0) {
        throw "MQTT connection closed while reading packet header."
    }
    $multiplier = 1
    $remainingLength = 0
    do {
        $digit = $Stream.ReadByte()
        if ($digit -lt 0) {
            throw "MQTT connection closed while reading remaining length."
        }
        $remainingLength += ($digit -band 127) * $multiplier
        $multiplier *= 128
        if ($multiplier -gt 128 * 128 * 128 * 128) {
            throw "Invalid MQTT remaining length encoding."
        }
    } while (($digit -band 128) -ne 0)

    $body = New-Object byte[] $remainingLength
    $offset = 0
    while ($offset -lt $remainingLength) {
        $read = $Stream.Read($body, $offset, $remainingLength - $offset)
        if ($read -le 0) {
            throw "MQTT connection closed while reading packet body."
        }
        $offset += $read
    }
    return [PSCustomObject]@{
        type = [byte] $first
        body = [byte[]] $body
    }
}

function New-MqttConnection {
    param(
        [Parameter(Mandatory = $true)] [string] $HostName,
        [Parameter(Mandatory = $true)] [int] $Port,
        [Parameter(Mandatory = $true)] [string] $ClientId,
        [Parameter(Mandatory = $true)] [int] $ConnectTimeout,
        [Parameter(Mandatory = $true)] [int] $AckTimeout
    )
    $client = [System.Net.Sockets.TcpClient]::new()
    $async = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne($ConnectTimeout)) {
        $client.Close()
        throw "Timed out connecting to MQTT broker $HostName`:$Port."
    }
    $client.EndConnect($async)
    $client.ReceiveTimeout = $AckTimeout
    $client.SendTimeout = $AckTimeout
    $stream = $client.GetStream()
    $variableHeader = (ConvertTo-MqttUtf8 -Value "MQTT") + [byte[]] @(4, 2, 0, 60)
    $payload = ConvertTo-MqttUtf8 -Value $ClientId
    Send-MqttPacket -Stream $stream -PacketType 0x10 -Body ([byte[]] $variableHeader + $payload)
    $connack = Read-MqttPacket -Stream $stream
    if ($connack.type -ne 0x20 -or $connack.body.Length -lt 2 -or $connack.body[1] -ne 0) {
        $client.Close()
        throw "MQTT broker rejected CONNECT for client $ClientId."
    }
    return [PSCustomObject]@{
        client = $client
        stream = $stream
        clientId = $ClientId
        connected = $true
        packetId = 1
    }
}

function Close-MqttConnection {
    param([AllowNull()] [object] $Connection)
    if ($null -eq $Connection) {
        return
    }
    try {
        if ($Connection.connected -and $null -ne $Connection.stream) {
            Send-MqttPacket -Stream $Connection.stream -PacketType 0xe0 -Body ([byte[]] @())
        }
    } catch {
    } finally {
        if ($null -ne $Connection.client) {
            $Connection.client.Close()
        }
        $Connection.connected = $false
    }
}

function Publish-MqttReading {
    param(
        [Parameter(Mandatory = $true)] [object] $Connection,
        [Parameter(Mandatory = $true)] [string] $Topic,
        [Parameter(Mandatory = $true)] [string] $Payload,
        [Parameter(Mandatory = $true)] [int] $PublishQos
    )
    $topicBytes = ConvertTo-MqttUtf8 -Value $Topic
    $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($Payload)
    $packetId = 0
    $packetIdBytes = [byte[]] @()
    if ($PublishQos -eq 1) {
        $packetId = [int] $Connection.packetId
        $Connection.packetId = if ($Connection.packetId -ge 65535) { 1 } else { $Connection.packetId + 1 }
        $packetIdBytes = [byte[]] @([byte] (($packetId -shr 8) -band 0xff), [byte] ($packetId -band 0xff))
    }
    $packetType = if ($PublishQos -eq 1) { [byte] 0x32 } else { [byte] 0x30 }
    Send-MqttPacket -Stream $Connection.stream -PacketType $packetType -Body ([byte[]] $topicBytes + $packetIdBytes + $payloadBytes)
    if ($PublishQos -eq 1) {
        $ack = Read-MqttPacket -Stream $Connection.stream
        $ackPacketId = if ($ack.body.Length -ge 2) { ([int] $ack.body[0] -shl 8) + [int] $ack.body[1] } else { -1 }
        if ($ack.type -ne 0x40 -or $ackPacketId -ne $packetId) {
            throw "Expected PUBACK packet $packetId but received type=$($ack.type) packetId=$ackPacketId."
        }
    }
}

$planPath = Join-Path $RunDirectory "generation-manifest.json"
if (-not (Test-Path -LiteralPath $planPath)) {
    throw "generation-manifest.json not found in run directory: $RunDirectory"
}

$plan = Get-Content -Raw -LiteralPath $planPath | ConvertFrom-Json
if ($plan.protocol -ne "mqtt") {
    throw "generation-manifest.json protocol is '$($plan.protocol)', expected mqtt."
}
if ([int] $plan.workerIndex -ne $WorkerIndex) {
    throw "generation-manifest.json workerIndex is '$($plan.workerIndex)', expected $WorkerIndex."
}
if ($Qos -ne [int] $plan.mqtt.qos) {
    throw "Requested QoS $Qos does not match generation plan QoS $($plan.mqtt.qos)."
}
if ($ConnectionCount -ne [int] $plan.mqtt.requestedConnections) {
    throw "Requested ConnectionCount $ConnectionCount does not match generation plan requestedConnections $($plan.mqtt.requestedConnections)."
}

$runStart = if ($null -ne $plan.runEpoch -and $null -ne $plan.runEpoch.iso) {
    [DateTimeOffset]::Parse([string] $plan.runEpoch.iso).UtcDateTime
} else {
    (Get-Date).ToUniversalTime()
}
$wallClockStart = (Get-Date).ToUniversalTime()
$deadline = if ($MaxDurationSeconds -gt 0) { $wallClockStart.AddSeconds($MaxDurationSeconds) } else { $null }

function Test-MqttDeadline {
    if ($null -eq $deadline) {
        return $false
    }
    return ((Get-Date).ToUniversalTime() -ge $deadline)
}

function Wait-MqttSchedule {
    param([Parameter(Mandatory = $true)] [object] $Reading)
    if ($DisableScheduleWait -or $DryRun) {
        return $true
    }

    $target = $wallClockStart.AddSeconds([int64] $Reading.recordedAtOffsetSeconds)
    while ((Get-Date).ToUniversalTime() -lt $target) {
        if (Test-MqttDeadline) {
            return $false
        }
        $remaining = ($target - (Get-Date).ToUniversalTime()).TotalMilliseconds
        Start-Sleep -Milliseconds ([int] [math]::Min(1000, [math]::Max(1, $remaining)))
    }
    return $true
}

$accepted = @()
$unknown = @()
$transportErrors = @()
$reconnectAttempts = 0
$droppedScheduled = 0
$connections = @{}
$unavailableConnectionSlots = @{}

try {
    if (-not $DryRun) {
        for ($slot = 0; $slot -lt $ConnectionCount; $slot++) {
            $clientId = "voltcanary-capacity-$($plan.runId)-worker-$WorkerIndex-conn-$slot"
            try {
                $connections[$slot] = New-MqttConnection -HostName $BrokerHost -Port $BrokerPort -ClientId $clientId -ConnectTimeout $ConnectTimeoutMillis -AckTimeout $AckTimeoutMillis
            } catch {
                $unavailableConnectionSlots[$slot] = $_.Exception.Message
            }
        }
    }

    $readings = @($plan.readings)
    for ($readingIndex = 0; $readingIndex -lt $readings.Count; $readingIndex++) {
        $reading = $readings[$readingIndex]
        $sequence = [int64] $reading.sequence
        $slot = [int] ($sequence % [int64] $ConnectionCount)
        $clientId = "voltcanary-capacity-$($plan.runId)-worker-$WorkerIndex-conn-$slot"
        $metrics = [ordered]@{}
        foreach ($metricName in @($reading.metricNames)) {
            $metrics[$metricName] = Get-CapacityMetricValue -Sequence $sequence -MetricName $metricName
        }
        $timestamp = $runStart.AddSeconds([int64] $reading.recordedAtOffsetSeconds).ToString("o")
        $payload = [PSCustomObject]@{
            timestamp = $timestamp
            metrics = $metrics
        } | ConvertTo-Json -Depth 20 -Compress
        $topic = "voltcanary/sources/$($reading.sourceId)/telemetry"

        if ($DryRun) {
            $unknown += $sequence
            continue
        }
        if (-not (Wait-MqttSchedule -Reading $reading)) {
            $droppedScheduled += ($readings.Count - $readingIndex)
            break
        }
        if ($unavailableConnectionSlots.ContainsKey($slot)) {
            $unknown += $sequence
            $transportErrors += $sequence
            continue
        }

        try {
            if (-not $connections.ContainsKey($slot) -or -not $connections[$slot].connected) {
                if ($connections.ContainsKey($slot)) {
                    $reconnectAttempts += 1
                    Close-MqttConnection -Connection $connections[$slot]
                }
                $connections[$slot] = New-MqttConnection -HostName $BrokerHost -Port $BrokerPort -ClientId $clientId -ConnectTimeout $ConnectTimeoutMillis -AckTimeout $AckTimeoutMillis
            }
            Publish-MqttReading -Connection $connections[$slot] -Topic $topic -Payload $payload -PublishQos $Qos
            $accepted += $sequence
        } catch {
            $unknown += $sequence
            $transportErrors += $sequence
            if ($connections.ContainsKey($slot)) {
                Close-MqttConnection -Connection $connections[$slot]
            }
        }
    }
} finally {
    foreach ($connection in $connections.Values) {
        Close-MqttConnection -Connection $connection
    }
}

$observedAttempts = [int64] (@($accepted).Count + @($unknown).Count)
$result = New-CapacityGenerationResult `
    -Plan $plan `
    -AcceptedSequences $accepted `
    -UnknownSequences $unknown `
    -TransportErrorSequences $transportErrors `
    -ObservedTransportAttempts $observedAttempts `
    -DroppedScheduledReadings ([int64] $droppedScheduled) `
    -ReconnectAttempts $reconnectAttempts `
    -ConnectionCount $ConnectionCount `
    -Qos $Qos `
    -ClientVersion "PowerShell raw MQTT 3.1.1 TCP publisher"
if ($DryRun) {
    $result.status = "NOT_RUN"
}
Write-CapacityGenerationResult -RunDirectory $RunDirectory -Result $result | Out-Null
Write-Host "MQTT generation $($result.status): $RunDirectory"
