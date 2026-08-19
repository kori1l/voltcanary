$ErrorActionPreference = "Stop"

function New-MlVerificationRunContext {
    param([string] $Prefix = "ml-full")

    $runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
    return [PSCustomObject]@{
        RunId = $runId
        Namespace = "$Prefix-$runId"
        CorrelationId = "$Prefix-verification-$runId"
        StartedAt = (Get-Date).ToUniversalTime()
    }
}

function ConvertTo-MlVerificationJson {
    param([object] $Value)
    return ($Value | ConvertTo-Json -Depth 60 -Compress)
}

function Invoke-MlVerificationApi {
    param(
        [Parameter(Mandatory = $true)] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [object] $Body = $null,
        [hashtable] $Headers = @{}
    )

    $uri = "$BaseUrl$Path"
    try {
        if ($null -ne $Body) {
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $Headers -Body (ConvertTo-MlVerificationJson $Body) -ContentType "application/json"
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

function New-MlVerificationAuthenticatedUser {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [object] $Context,
        [string] $EmailPrefix = "ml-verification"
    )

    $email = "$EmailPrefix-$($Context.RunId)@example.test"
    $password = "VoltCanary!2026-$($Context.RunId)"
    Invoke-MlVerificationApi POST "/auth/register" $BaseUrl -Body @{ email = $email; password = $password } | Out-Null
    $login = Invoke-MlVerificationApi POST "/auth/login" $BaseUrl -Body @{ email = $email; password = $password }
    Assert-MlVerificationValue $login.accessToken "Login response did not include accessToken"
    $headers = @{
        Authorization = "Bearer $($login.accessToken)"
        "X-Correlation-Id" = $Context.CorrelationId
    }
    $me = Invoke-MlVerificationApi GET "/auth/me" $BaseUrl -Headers $headers
    $account = Invoke-MlVerificationApi GET "/accounts/current" $BaseUrl -Headers $headers
    Assert-MlVerificationValue $account.id "Current account was not created"

    return [PSCustomObject]@{
        Email = $email
        Password = $password
        AccessToken = "$($login.accessToken)"
        Headers = $headers
        User = $me
        Account = $account
    }
}

function New-MlVerificationDeviceSource {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [Parameter(Mandatory = $true)] [object] $Context,
        [string] $Profile = "normal-washer",
        [string] $DeviceType = "GENERIC_APPLIANCE"
    )

    $device = Invoke-MlVerificationApi POST "/devices" $BaseUrl -Headers $Headers -Body @{
        locationId = $null
        name = "$Profile $($Context.RunId)"
        type = $DeviceType
        manufacturer = "VoltCanary"
        model = "verification"
        metadata = @{
            verificationRunId = $Context.RunId
            semanticDeviceType = "washer"
            profile = $Profile
        }
    }
    Assert-MlVerificationValue $device.id "Device creation did not return id"

    $sourceExternalId = "$Profile-$($Context.RunId)"
    $source = Invoke-MlVerificationApi POST "/devices/$($device.id)/data-sources" $BaseUrl -Headers $Headers -Body @{
        sourceType = "API"
        protocol = "REST"
        externalId = $sourceExternalId
    }
    $sourceToken = Invoke-MlVerificationApi POST "/devices/$($device.id)/data-sources/$($source.id)/token" $BaseUrl -Headers $Headers
    Assert-MlVerificationValue $sourceToken.token "Source token response did not include token"

    return [PSCustomObject]@{
        Device = $device
        Source = $source
        SourceId = $sourceExternalId
        SourceHeaders = @{
            Authorization = "Bearer $($sourceToken.token)"
            "X-Correlation-Id" = $Context.CorrelationId
        }
    }
}

function Submit-MlVerificationTelemetryBatch {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
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

        $response = Invoke-MlVerificationApi POST "/ingestion/imports/readings/batch" $BaseUrl -Headers $Headers -Body @{ readings = $chunk }
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

function Wait-MlVerificationCondition {
    param(
        [Parameter(Mandatory = $true)] [string] $Description,
        [Parameter(Mandatory = $true)] [scriptblock] $Condition,
        [int] $TimeoutSeconds = 420,
        [int] $DelaySeconds = 5
    )

    $deadline = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
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

function Assert-MlVerification {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-MlVerificationValue {
    param([object] $Value, [string] $Message)
    if ($null -eq $Value -or "$Value" -eq "") {
        throw $Message
    }
}

function ConvertTo-MlVerificationInstant {
    param([datetime] $Value)
    return $Value.ToUniversalTime().ToString("o")
}

function New-MlWasherTelemetryProfile {
    param(
        [Parameter(Mandatory = $true)] [string] $Profile,
        [Parameter(Mandatory = $true)] [string] $DeviceId,
        [Parameter(Mandatory = $true)] [string] $SourceId,
        [Parameter(Mandatory = $true)] [datetime] $Start,
        [Parameter(Mandatory = $true)] [datetime] $End
    )

    $readings = New-Object System.Collections.Generic.List[object]
    $timestamp = $Start
    $index = 0
    $outcomeAt = $Start.AddDays(21)
    while ($timestamp -le $End) {
        $days = ($timestamp - $Start).TotalDays
        $basePower = 820.0 + ([Math]::Sin($index / 3.0) * 18.0)
        $profilePower = switch ($Profile) {
            "normal-washer" { $basePower }
            "spiking-washer" {
                if (($index % 9) -eq 0) { 1750.0 + ([Math]::Sin($index) * 80.0) } else { $basePower }
            }
            "degrading-washer" {
                $isPreFailure = $timestamp -gt $outcomeAt.AddDays(-7) -and $timestamp -lt $outcomeAt
                if ($isPreFailure) { 1030.0 + ($days * 9.0) + ([Math]::Sin($index) * 24.0) } else { $basePower }
            }
            "maintenance-washer" {
                if ($timestamp -gt $Start.AddDays(10) -and $timestamp -lt $Start.AddDays(10).AddHours(6)) { 1800.0 } else { $basePower }
            }
            default { throw "Unknown ML verification telemetry profile: $Profile" }
        }

        $readings.Add([PSCustomObject]@{
            deviceId = $DeviceId
            sourceId = $SourceId
            timestamp = ConvertTo-MlVerificationInstant $timestamp
            metrics = [PSCustomObject]@{
                activePowerW = [Math]::Round($profilePower, 3)
                voltageV = [Math]::Round(229.0 + ([Math]::Sin($index / 5.0) * 2.0), 3)
                currentA = [Math]::Round($profilePower / 229.0, 3)
                energyWh = [Math]::Round(50000.0 + ($index * 20.0), 3)
            }
        })
        $timestamp = $timestamp.AddHours(4)
        $index++
    }

    return $readings
}

function New-MlVerificationMaintenanceWindow {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [Parameter(Mandatory = $true)] [object] $Context,
        [Parameter(Mandatory = $true)] [string] $DeviceId,
        [Parameter(Mandatory = $true)] [datetime] $StartAt,
        [Parameter(Mandatory = $true)] [datetime] $EndAt,
        [string] $Title = "",
        [bool] $SuppressAlerts = $true,
        [bool] $ExcludeFromAnalytics = $false,
        [bool] $ExcludeFromMl = $true
    )

    $operationTitle = if ($Title) { $Title } else { "ML verification maintenance $($Context.RunId)" }
    $operation = Invoke-MlVerificationApi POST "/maintenance-operations" $BaseUrl -Headers $Headers -Body @{
        title = $operationTitle
        description = "Synthetic maintenance interval for ML verification."
        deviceIds = @($DeviceId)
        startAt = ConvertTo-MlVerificationInstant $StartAt
        endAt = ConvertTo-MlVerificationInstant $EndAt
        suppressAlerts = $SuppressAlerts
        excludeFromAnalytics = $ExcludeFromAnalytics
        excludeFromMl = $ExcludeFromMl
    }
    Assert-MlVerificationValue $operation.id "Maintenance operation creation did not return id"
    return $operation
}

function New-MlVerificationCorrectiveMaintenanceOutcome {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [Parameter(Mandatory = $true)] [object] $Context,
        [Parameter(Mandatory = $true)] [string] $DeviceId,
        [Parameter(Mandatory = $true)] [string] $MaintenanceOperationId,
        [Parameter(Mandatory = $true)] [datetime] $ObservedAt,
        [datetime] $ResolvedAt = [datetime]::MinValue,
        [string] $FailureMode = "bearing-drift",
        [string] $Component = "motor",
        [string] $Severity = "HIGH"
    )

    $resolved = if ($ResolvedAt -eq [datetime]::MinValue) { $ObservedAt.AddHours(2) } else { $ResolvedAt }
    $outcome = Invoke-MlVerificationApi POST "/maintenance-operations/$MaintenanceOperationId/outcomes" $BaseUrl -Headers $Headers -Body @{
        deviceId = $DeviceId
        maintenanceWindowId = $null
        observedAt = ConvertTo-MlVerificationInstant $ObservedAt
        resolvedAt = ConvertTo-MlVerificationInstant $resolved
        outcomeType = "CORRECTIVE_MAINTENANCE"
        failureMode = $FailureMode
        component = $Component
        severity = $Severity
        downtimeMinutes = [int] [Math]::Max(1, ($resolved - $ObservedAt).TotalMinutes)
        technicianNotes = "Synthetic corrective maintenance outcome for ML verification $($Context.RunId)."
        source = "OPERATOR"
        confidence = 0.95
    }
    Assert-MlVerificationValue $outcome.id "Maintenance outcome creation did not return id"
    return $outcome
}

function New-MlVerificationDeviceProfileFixture {
    param(
        [Parameter(Mandatory = $true)] [string] $BaseUrl,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [Parameter(Mandatory = $true)] [object] $Context,
        [Parameter(Mandatory = $true)] [string] $Profile,
        [Parameter(Mandatory = $true)] [datetime] $Start,
        [Parameter(Mandatory = $true)] [datetime] $End,
        [switch] $CreateMaintenanceWindow,
        [switch] $CreateCorrectiveOutcome
    )

    $fixture = New-MlVerificationDeviceSource -BaseUrl $BaseUrl -Headers $Headers -Context $Context -Profile $Profile
    $readings = New-MlWasherTelemetryProfile -Profile $Profile -DeviceId "$($fixture.Device.id)" -SourceId "$($fixture.SourceId)" -Start $Start -End $End
    $ingestion = Submit-MlVerificationTelemetryBatch -BaseUrl $BaseUrl -Readings $readings -Headers $fixture.SourceHeaders
    Assert-MlVerification ($ingestion.acceptedCount -gt 0) "Telemetry batch was not accepted for $Profile"
    Assert-MlVerification ($ingestion.rejectedCount -eq 0) "Telemetry batch rejected $($ingestion.rejectedCount) readings for $Profile"

    $maintenanceWindow = $null
    if ($CreateMaintenanceWindow -or $Profile -eq "maintenance-washer") {
        $windowStart = $Start.AddDays(10)
        $maintenanceWindow = New-MlVerificationMaintenanceWindow `
            -BaseUrl $BaseUrl `
            -Headers $Headers `
            -Context $Context `
            -DeviceId "$($fixture.Device.id)" `
            -StartAt $windowStart `
            -EndAt $windowStart.AddHours(6) `
            -Title "ML verification excluded maintenance $($Context.RunId)" `
            -SuppressAlerts $true `
            -ExcludeFromAnalytics $false `
            -ExcludeFromMl $true
    }

    $correctiveOutcome = $null
    if ($CreateCorrectiveOutcome -or $Profile -eq "degrading-washer") {
        $outcomeAt = $Start.AddDays(21)
        $operation = New-MlVerificationMaintenanceWindow `
            -BaseUrl $BaseUrl `
            -Headers $Headers `
            -Context $Context `
            -DeviceId "$($fixture.Device.id)" `
            -StartAt $outcomeAt.AddHours(-1) `
            -EndAt $outcomeAt.AddHours(2) `
            -Title "ML verification corrective maintenance $($Context.RunId)" `
            -SuppressAlerts $false `
            -ExcludeFromAnalytics $false `
            -ExcludeFromMl $false
        $correctiveOutcome = New-MlVerificationCorrectiveMaintenanceOutcome `
            -BaseUrl $BaseUrl `
            -Headers $Headers `
            -Context $Context `
            -DeviceId "$($fixture.Device.id)" `
            -MaintenanceOperationId "$($operation.id)" `
            -ObservedAt $outcomeAt `
            -ResolvedAt $outcomeAt.AddHours(2)
    }

    return [PSCustomObject]@{
        DeviceFixture = $fixture
        ReadingsSubmitted = $ingestion
        MaintenanceWindow = $maintenanceWindow
        CorrectiveOutcome = $correctiveOutcome
    }
}

function Write-MlVerificationSummary {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [object] $Summary
    )

    $directory = Split-Path -Parent $Path
    if ($directory) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    ($Summary | ConvertTo-Json -Depth 80) | Set-Content -Path $Path -Encoding UTF8
}
