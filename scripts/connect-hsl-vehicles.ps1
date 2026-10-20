param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateRange(1, 20)][int]$Count = 3,
    [ValidateRange(0, 10)][int]$BusCount = 0,
    [string[]]$BusRoutes = @("97N", "570N", "600", "30"),
    [string]$DashboardId
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot/..").Path
$privateDir = Join-Path $repoRoot "docker-data/hsl"
$configPath = Join-Path $privateDir "config.json"
$composeArgs = @("compose", "-f", (Join-Path $repoRoot "docker-compose.yml"), "-f", (Join-Path $repoRoot "deploy/docker-compose/hsl.yml"), "--profile", "hsl")

function Invoke-HslApi([string]$Method, [string]$Path, $Body = $null, [hashtable]$ExtraHeaders = @{}) {
    $headers = @{}
    if ($script:hslHeaders) { foreach ($key in $script:hslHeaders.Keys) { $headers[$key] = $script:hslHeaders[$key] } }
    foreach ($key in $ExtraHeaders.Keys) { $headers[$key] = $ExtraHeaders[$key] }
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) { $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 30 -Compress)); $request.ContentType = "application/json; charset=utf-8" }
    try { $response = Invoke-RestMethod @request }
    catch {
        $detail = $_.ErrorDetails.Message
        if (!$detail -and $_.Exception.Response) {
            $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
            $reader.Dispose()
        }
        throw "HSL setup API failed: $Method $Path : $detail"
    }
    if ($response -is [array]) {
        foreach ($item in $response) { Write-Output $item }
    } else { Write-Output $response }
}

# Owner credentials stay in memory. Only per-source ingress tokens are persisted privately.
$login = Invoke-HslApi POST "/auth/login" @{ email = $Email; password = $Password }
if (!$login.accessToken) { throw "Login did not return an access token" }
$script:hslHeaders = @{ Authorization = "Bearer $($login.accessToken)" }
$null = New-Item -ItemType Directory -Force -Path $privateDir
$previous = $null
if (Test-Path -LiteralPath $configPath) {
    $previous = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $previousTrams = @($previous.vehicles | Where-Object { !$_.mode -or $_.mode -eq "tram" })
    $previousBuses = @($previous.vehicles | Where-Object { $_.mode -eq "bus" })
    if (!$PSBoundParameters.ContainsKey("Count")) { $Count = $previousTrams.Count }
    if (!$PSBoundParameters.ContainsKey("BusCount")) { $BusCount = $previousBuses.Count }
    if ($previous.ownerEmail -ne $Email) { throw "Existing connector belongs to another owner; use a separate deployment" }
    if ($previousTrams.Count -gt $Count -or $previousBuses.Count -gt $BusCount) { throw "Refusing to shrink the existing allowlist implicitly" }
    if ($Count + $BusCount -gt 20) { throw "Fleet cannot exceed twenty vehicles" }
    $selected = $previousTrams
    if ($selected.Count -lt $Count) {
        $discovery = & docker @composeArgs run --rm --no-deps hsl-connector python discover.py $Count
        if ($LASTEXITCODE -ne 0) { throw "Public HSL discovery failed" }
        $additional = @(($discovery | ConvertFrom-Json) | Where-Object { $_.key -notin $selected.key })
        $selected += @($additional | Select-Object -First ($Count - $selected.Count))
    }
    $selected += $previousBuses
} else {
    $discovery = & docker @composeArgs run --rm --no-deps hsl-connector python discover.py $Count
    if ($LASTEXITCODE -ne 0) { throw "Public HSL discovery failed" }
    $selected = @($discovery | ConvertFrom-Json)
}

if ($Count + $BusCount -gt 20) { throw "Fleet cannot exceed twenty vehicles" }
$selectedBuses = @($selected | Where-Object { $_.mode -eq "bus" })
if ($selectedBuses.Count -lt $BusCount) {
    $discovery = & docker @composeArgs run --rm --no-deps hsl-connector python discover.py $BusCount bus ($BusRoutes -join ',')
    if ($LASTEXITCODE -ne 0) { throw "Public HSL bus discovery failed" }
    $additional = @(($discovery | ConvertFrom-Json) | Where-Object { $_.key -notin $selectedBuses.key })
    $selected += @($additional | Select-Object -First ($BusCount - $selectedBuses.Count))
}
if ($selected.Count -ne $Count + $BusCount) { throw "Not enough new vehicles to complete the selected fleet" }

$devices = @(Invoke-HslApi GET "/devices?type=VEHICLE&search=HSL&limit=100")
$bindings = @()
foreach ($vehicle in $selected) {
    if ($vehicle.key -notmatch '^[0-9]{4}-[0-9]{5}$') { throw "Invalid HSL identity" }
    $key = $vehicle.key
    $mode = if ($vehicle.mode) { $vehicle.mode } else { "tram" }
    if ($mode -notin @("tram", "bus")) { throw "Unsupported HSL mode" }
    $sourceId = "hsl-$mode-$key"
    $name = "HSL $mode $key"
    $matches = @($devices | Where-Object { $_.metadata.externalVehicleId -eq $key -and $_.metadata.feedProvider -eq "HSL" -and (($_.metadata.transportMode -eq $mode) -or (!$_.metadata.transportMode -and $mode -eq "tram")) })
    if ($matches.Count -gt 1) { throw "Ambiguous HSL device identity $key" }
    $device = $matches | Select-Object -First 1
    if (!$device) {
        $device = Invoke-HslApi POST "/devices" @{
            name = $name; type = "VEHICLE"; locationId = $null; manufacturer = "HSL public transport"; model = "Real $mode GPS"
            metadata = @{ setupMode = "real"; feedProvider = "HSL"; transportMode = $mode; externalVehicleId = $key; city = "Helsinki"; attribution = "HSL HFP open data"; initialLine = $vehicle.line }
        }
    }
    $sources = @(Invoke-HslApi GET "/devices/$($device.id)/data-sources")
    $sourceMatches = @($sources | Where-Object { $_.externalId -eq $sourceId })
    if ($sourceMatches.Count -gt 1) { throw "Ambiguous HSL source $key" }
    $source = $sourceMatches | Select-Object -First 1
    if (!$source) {
        $source = Invoke-HslApi POST "/devices/$($device.id)/data-sources" @{
            sourceType = "API"; protocol = "REST"; externalId = $sourceId
            adapterConfig = @{ provider = "HSL_HFP"; transportMode = $mode; externalVehicleId = $key; attribution = "HSL HFP open data" }
        }
    }
    if ($source.sourceType -ne "API" -or $source.protocol -ne "REST") { throw "Incompatible HSL source $key" }
    $saved = @($previous.vehicles | Where-Object { $_.key -eq $key -and $_.deviceId -eq $device.id -and $_.dataSourceId -eq $source.id }) | Select-Object -First 1
    $sourceToken = $saved.sourceToken
    if (!$sourceToken) { $sourceToken = (Invoke-HslApi POST "/devices/$($device.id)/data-sources/$($source.id)/token").token }
    if (!$sourceToken) { throw "Source token issuance failed" }
    $bindings += @{ key = $key; mode = $mode; deviceId = $device.id; dataSourceId = $source.id; sourceId = $sourceId; sourceToken = $sourceToken; line = $vehicle.line }
}
$privateConfig = @{ ownerEmail = $Email; vehicles = $bindings }
$temporaryPath = "$configPath.tmp"
[IO.File]::WriteAllText($temporaryPath, ($privateConfig | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
Move-Item -LiteralPath $temporaryPath -Destination $configPath -Force

$catalog = Invoke-HslApi GET "/dashboards?limit=100"
$dashboardMatches = @($catalog.items | Where-Object { $_.name -eq "Helsinki HSL" })
if ($dashboardMatches.Count -gt 1) { throw "Ambiguous Helsinki HSL dashboard" }
$dashboard = $dashboardMatches | Select-Object -First 1
if ($DashboardId) {
    $dashboard = (Invoke-HslApi GET "/dashboards/$DashboardId").dashboard
}
$updatedDraft = $false
if ($true) {
    $vehicleSources = @{}
    foreach ($binding in $bindings) { $vehicleSources[$binding.deviceId] = $binding.sourceId }
    $widgetIds = @("hsl-fleet")
    $widgets = @(@{ id = "hsl-fleet"; type = "vehicle-map"; typeVersion = 1; title = "Helsinki · all connected vehicles"; binding = @{ mode = "FIXED"; deviceIds = @($bindings | ForEach-Object { $_.deviceId }) }; query = @{ vehicleSources = $vehicleSources }; presentation = @{ attribution = "HSL HFP open data" }; navigation = @{ href = "/devices" }; payload = @{} })
    $desktop = @(@{ widgetId = "hsl-fleet"; x = 0; y = 0; w = 12; h = 13 })
    $tablet = @(@{ widgetId = "hsl-fleet"; x = 0; y = 0; w = 6; h = 13 })
    $mobile = @(@{ widgetId = "hsl-fleet"; x = 0; y = 0; w = 1; h = 13 })
    $definition = @{ schemaVersion = 1; name = "Helsinki HSL"; description = "Real Helsinki tram and bus positions. Source: HSL HFP open data. No simulated measurements."
        defaultContext = @{ scope = @{ mode = "ALL" }; time = @{ mode = "RELATIVE"; preset = "LAST_6_HOURS"; timezone = "Europe/Helsinki" } }
        sections = @(); screens = @(@{ id = "helsinki"; title = "Helsinki"; widgetIds = $widgetIds; grid = @{ desktop = @{ columns = 12; rowHeight = 48; gap = 12 }; tablet = @{ columns = 6; rowHeight = 48; gap = 12 }; mobile = @{ columns = 1; rowHeight = 48; gap = 12 } } })
        widgets = $widgets; layouts = @{ desktop = $desktop; tablet = $tablet; mobile = $mobile }
    }
    if ($dashboard) {
        $existingDraft = Invoke-HslApi GET "/dashboards/$($dashboard.id)/draft"
        $currentWidgets = @($existingDraft.definition.widgets)
        $currentSources = $currentWidgets[0].query.vehicleSources
        $sameFleet = $currentWidgets.Count -eq 1 -and $null -ne $currentSources -and @($currentSources.psobject.Properties).Count -eq $bindings.Count
        foreach ($binding in $bindings) { if (!$currentSources -or $currentSources.($binding.deviceId) -ne $binding.sourceId) { $sameFleet = $false } }
        if (!$sameFleet) {
            $null = Invoke-HslApi PUT "/dashboards/$($dashboard.id)/draft" @{ definition = $definition; baseRevision = $dashboard.publishedRevision } @{ "If-Match" = "`"$($existingDraft.etag)`"" }
            $updatedDraft = $true
        }
    } else {
        $dashboard = Invoke-HslApi POST "/dashboards" @{ definition = $definition } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
    }
}
$draft = Invoke-HslApi GET "/dashboards/$($dashboard.id)/draft"
$draftSources = @($draft.definition.widgets | ForEach-Object { if ($_.query.vehicleSources) { $_.query.vehicleSources.psobject.Properties | ForEach-Object { $_.Value } } else { $_.query.sourceId } })
foreach ($binding in $bindings) { if ($draftSources -notcontains $binding.sourceId) { throw "Existing Helsinki dashboard has different bindings" } }
$view = Invoke-HslApi GET "/dashboards/$($dashboard.id)"
$publishedWidgets = @($view.definition.widgets)
$publishedSources = if ($publishedWidgets.Count -eq 1) { $publishedWidgets[0].query.vehicleSources } else { $null }
$publishedMatches = $null -ne $publishedSources -and @($publishedSources.psobject.Properties).Count -eq $bindings.Count
foreach ($binding in $bindings) { if (!$publishedSources -or $publishedSources.($binding.deviceId) -ne $binding.sourceId) { $publishedMatches = $false } }
if ($updatedDraft -or !$publishedMatches -or $view.dashboard.publishedRevision -eq 0 -or $null -eq $view.definition) {
    $validation = Invoke-HslApi POST "/dashboards/$($dashboard.id)/query-validation" @{ draftEtag = $draft.etag }
    if (@($validation.panels | Where-Object { $_.status -ne "valid" }).Count) { throw "HSL dashboard binding validation failed" }
    $null = Invoke-HslApi POST "/dashboards/$($dashboard.id)/publish" @{ draftEtag = $draft.etag } @{ "If-Match" = "`"$($draft.etag)`""; "Idempotency-Key" = [guid]::NewGuid().ToString() }
}
& docker @composeArgs up -d --no-deps --force-recreate hsl-connector
if ($LASTEXITCODE -ne 0) { throw "HSL connector start failed" }
[pscustomobject]@{
    dashboardId = $dashboard.id; dashboardUrl = "http://localhost:3000/dashboards/$($dashboard.id)"
    fleetUrl = "http://localhost:3000/vehicles"
    devices = @($bindings | ForEach-Object { @{ key = $_.key; deviceId = $_.deviceId; sourceId = $_.sourceId } })
} | ConvertTo-Json -Depth 6
