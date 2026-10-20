param(
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceName = "Demo car",
    [string]$DashboardName = "Vehicle GPS"
)

$ErrorActionPreference = "Stop"
function Invoke-VehicleApi([string]$Method, [string]$Path, $Body = $null, [hashtable]$ExtraHeaders = @{}) {
    $requestHeaders = @{}
    if ($script:vehicleHeaders) { foreach ($key in $script:vehicleHeaders.Keys) { $requestHeaders[$key] = $script:vehicleHeaders[$key] } }
    foreach ($key in $ExtraHeaders.Keys) { $requestHeaders[$key] = $ExtraHeaders[$key] }
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $requestHeaders; TimeoutSec = 30 }
    if ($null -ne $Body) { $request.Body = ($Body | ConvertTo-Json -Depth 30 -Compress); $request.ContentType = "application/json" }
    Invoke-RestMethod @request
}

# Credentials are kept in this process only; never written to a summary or log.
$login = Invoke-VehicleApi POST "/auth/login" @{ email = $Email; password = $Password }
if (!$login.accessToken) { throw "Login did not return an access token" }
$script:vehicleHeaders = @{ Authorization = "Bearer $($login.accessToken)" }
$devices = @(Invoke-VehicleApi GET "/devices?type=VEHICLE&search=$([uri]::EscapeDataString($DeviceName))&limit=100")
$matches = @($devices | Where-Object { $_.name -eq $DeviceName })
if ($matches.Count -gt 1) { throw "Multiple matching demo cars; narrow DeviceName" }
$car = $matches | Select-Object -First 1
if (!$car) { $car = Invoke-VehicleApi POST "/devices" @{ name = $DeviceName; type = "VEHICLE"; locationId = $null; manufacturer = "VoltCanary"; model = "GPS demo"; metadata = @{ setupMode = "demo"; preset = "CITY_LOOP" } } }
$sourceId = "sim-car-$($car.id.Substring(0,8))"
$sources = @(Invoke-VehicleApi GET "/devices/$($car.id)/data-sources")
$source = $sources | Where-Object { $_.externalId -eq $sourceId } | Select-Object -First 1
if (!$source) { $source = Invoke-VehicleApi POST "/devices/$($car.id)/data-sources" @{ sourceType = "SIMULATOR"; protocol = "REST"; externalId = $sourceId } }
if ($source.sourceType -ne "SIMULATOR" -or $source.protocol -ne "REST") { throw "Demo source has an incompatible type/protocol" }
$config = $null
try { $config = Invoke-VehicleApi GET "/simulation/devices/$($car.id)/config" }
catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
if (!$config) {
    $credential = Invoke-VehicleApi POST "/devices/$($car.id)/data-sources/$($source.id)/token"
    $template = Invoke-VehicleApi GET "/simulation/templates/VEHICLE_GPS?sourceId=$sourceId&preset=CITY_LOOP"
    # The demonstration remains available for a day; the product template stays 30m.
    $yaml = $template.yamlConfig.Replace("duration: 30m", "duration: 24h")
    $validation = Invoke-VehicleApi POST "/simulation/devices/$($car.id)/config/validate" @{ yamlConfig = $yaml }
    if (!$validation.valid) { throw "GPS template validation failed" }
    $config = Invoke-VehicleApi PUT "/simulation/devices/$($car.id)/config" @{ dataSourceId = $source.id; name = "$DeviceName GPS"; profile = "VEHICLE_GPS"; protocol = "HTTP"; yamlConfig = $yaml; sourceToken = $credential.token }
}
if ($config.profile -ne "VEHICLE_GPS" -or $config.dataSourceId -ne $source.id) { throw "Saved demo config does not match the GPS source" }
$status = Invoke-VehicleApi GET "/simulation/devices/$($car.id)/status"
if ($status.status -ne "RUNNING") { $status = Invoke-VehicleApi POST "/simulation/devices/$($car.id)/start" }

$catalog = Invoke-VehicleApi GET "/dashboards?limit=100"
$dashMatches = @($catalog.items | Where-Object { $_.name -eq $DashboardName })
if ($dashMatches.Count -gt 1) { throw "Multiple matching dashboards; narrow DashboardName" }
$dashboard = $dashMatches | Select-Object -First 1
if (!$dashboard) {
    $definition = @{
        schemaVersion = 1; name = $DashboardName; description = "Live GPS position and observed trail of the demo automobile"
        defaultContext = @{ scope = @{ mode = "ALL" }; time = @{ mode = "RELATIVE"; preset = "LAST_6_HOURS"; timezone = "Europe/Belgrade" } }
        sections = @(); screens = @(@{ id = "movement"; title = "Movement"; widgetIds = @("vehicle-map"); grid = @{ desktop = @{ columns = 12; rowHeight = 48; gap = 12 }; tablet = @{ columns = 6; rowHeight = 48; gap = 12 }; mobile = @{ columns = 1; rowHeight = 48; gap = 12 } } })
        widgets = @(@{ id = "vehicle-map"; type = "vehicle-map"; typeVersion = 1; title = $DeviceName; binding = @{ mode = "FIXED"; deviceIds = @($car.id) }; query = @{ sourceId = $sourceId }; presentation = @{ follow = $true }; navigation = @{ href = "/devices/$($car.id)" }; payload = @{} })
        layouts = @{ desktop = @(@{ widgetId = "vehicle-map"; x = 0; y = 0; w = 12; h = 11 }); tablet = @(@{ widgetId = "vehicle-map"; x = 0; y = 0; w = 6; h = 11 }); mobile = @(@{ widgetId = "vehicle-map"; x = 0; y = 0; w = 1; h = 10 }) }
    }
    $dashboard = Invoke-VehicleApi POST "/dashboards" @{ definition = $definition } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
}
$draft = Invoke-VehicleApi GET "/dashboards/$($dashboard.id)/draft"
$view = Invoke-VehicleApi GET "/dashboards/$($dashboard.id)"
if ($view.dashboard.publishedRevision -eq 0 -or $view.publishedRevision -eq 0 -or $null -eq $view.definition) {
    $valid = Invoke-VehicleApi POST "/dashboards/$($dashboard.id)/query-validation" @{ draftEtag = $draft.etag }
    if (@($valid.panels | Where-Object { $_.status -ne "valid" }).Count) { throw "Map binding validation failed: $($valid.panels | ConvertTo-Json -Depth 5 -Compress)" }
    $null = Invoke-VehicleApi POST "/dashboards/$($dashboard.id)/publish" @{ draftEtag = $draft.etag } @{ "If-Match" = "`"$($draft.etag)`""; "Idempotency-Key" = [guid]::NewGuid().ToString() }
}
[pscustomobject]@{ deviceId = $car.id; sourceId = $sourceId; dashboardId = $dashboard.id; runtime = $status.status; deviceUrl = "http://localhost:3000/devices/$($car.id)?tab=movement"; dashboardUrl = "http://localhost:3000/dashboards/$($dashboard.id)" } | ConvertTo-Json
