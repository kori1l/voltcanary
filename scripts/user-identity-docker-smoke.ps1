param(
    [string]$Gateway = "http://127.0.0.1:18080",
    [string]$Device = "http://127.0.0.1:18082",
    [string]$Dashboard = "http://127.0.0.1:18089",
    [string]$Telemetry = "http://127.0.0.1:18084",
    [string]$Analytics = "http://127.0.0.1:18085",
    [string]$Alerts = "http://127.0.0.1:18086",
    [string]$SummaryPath = "artifacts/user-identity-smoke-summary.json"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$checks = 0
$cleanup = @()
$dashboards = @()

function Request([string]$Base, [string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null, [int[]]$Expected = @(200)) {
    $uri = "$Base$Path"
    if ($uri -notmatch '^https?://[^/]+/') { throw "Invalid smoke endpoint: $uri" }
    $arguments = @{Uri=$uri; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=20}
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 25 -Compress
    }
    $content = $null
    try {
        $response = Invoke-WebRequest @arguments
        $status = [int]$response.StatusCode
        if ($response.Content) { $content = $response.Content | ConvertFrom-Json }
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
    }
    if ($status -notin $Expected) { throw "Unexpected HTTP $status for $Method $Base$Path; expected $Expected" }
    $script:checks++
    return $content
}

function Account {
    $email = "identity-smoke-" + [guid]::NewGuid().ToString() + "@example.test"
    $password = "Identity-smoke-2026!"
    $user = Request $Gateway POST "/auth/register" @{} @{email=$email;password=$password} @(200,201)
    $session = Request $Gateway POST "/auth/login" @{} @{email=$email;password=$password}
    $headers = @{Authorization=("Bearer " + $session.accessToken)}
    $account = Request $Device GET "/accounts/current" $headers
    # Exercise ordinary refresh without exposing the credentials in output or summaries.
    $session = Request $Gateway POST "/auth/refresh" @{} @{refreshToken=$session.refreshToken}
    return @{User=$user.userId;Account=$account.id;Headers=@{Authorization=("Bearer " + $session.accessToken)}}
}

try {
    $owner = Account
    $foreign = Account
    $forged = @{"X-User-Id"=$owner.User;"X-User-Email"="forged@example.test";"X-User-Role"="ADMIN";
        "X-Account-Id"=$owner.Account;"X-Workspace-Role"="OWNER";"X-Capabilities"="*";
        "X-Internal-Service-Token"="variable-smoke-internal"}
    foreach ($base in @($Gateway,$Device)) {
        foreach ($credential in @("", "Bearer invalid", "Bearer collector-token")) {
            $headers = $forged.Clone()
            if ($credential) { $headers.Authorization=$credential }
            $null = Request $base GET "/devices" $headers $null @(401)
            $null = Request $base POST "/devices" $headers @{name="Forbidden";type="FRIDGE"} @(401)
        }
        $headers = $forged.Clone()
        $headers.Authorization=$owner.Headers.Authorization
        $headers["X-User-Id"]=$foreign.User
        $headers["X-Account-Id"]=$foreign.Account
        $actual = Request $base GET "/accounts/current" $headers
        if ($actual.id -ne $owner.Account -or $actual.userId -ne $owner.User) { throw "Forged identity overrode JWT" }
        $createdDevice = Request $base POST "/devices" $headers @{name="Identity fixture";type="FRIDGE"} @(200,201)
        $cleanup += @{Id=$createdDevice.id;Headers=$owner.Headers}
        foreach ($method in @("GET","PATCH","DELETE")) {
            $body = if ($method -eq "PATCH") {@{name="Forbidden update";type="FRIDGE"}} else {$null}
            $null = Request $base $method "/devices/$($createdDevice.id)" $forged $body @(401)
            $foreignHeaders = $forged.Clone()
            $foreignHeaders.Authorization=$foreign.Headers.Authorization
            $null = Request $base $method "/devices/$($createdDevice.id)" $foreignHeaders $body @(403,404)
        }
        $null = Request $base PATCH "/devices/$($createdDevice.id)" $owner.Headers @{name="Verified update";type="FRIDGE"}
        $source = Request $base POST "/devices/$($createdDevice.id)/data-sources" $owner.Headers @{
            sourceType="SENSOR";protocol="REST";externalId=("identity-"+[guid]::NewGuid().ToString())} @(200,201)
        $null = Request $base GET "/devices/$($createdDevice.id)/data-sources/$($source.id)" $owner.Headers
        foreach ($entry in @(
            @{Base=$Telemetry;Path="/telemetry/devices/$($createdDevice.id)/metrics"},
            @{Base=$Analytics;Path="/analytics/devices/$($createdDevice.id)/risk-score"},
            @{Base=$Alerts;Path="/alerts"},
            @{Base=$Alerts;Path="/alert-rules"}
        )) {
            $null = Request $entry.Base GET $entry.Path $forged $null @(401)
        }
        $null = Request $Telemetry GET "/telemetry/devices/$($createdDevice.id)/metrics" $owner.Headers
        $null = Request $Telemetry GET "/telemetry/devices/$($createdDevice.id)/metrics" $foreignHeaders $null @(403,404)
    }
    foreach ($base in @($Gateway,$Dashboard)) {
        $definition = @{schemaVersion=1;name="Identity private draft";description="";defaultContext=@{};
            sections=@();widgets=@();layouts=@{};screens=@()}
        $createHeaders = $owner.Headers.Clone()
        $createHeaders["Idempotency-Key"]="identity-"+[guid]::NewGuid().ToString()
        $created = Request $base POST "/dashboards" $createHeaders @{definition=$definition} @(201)
        $dashboards += $created.id
        $null = Request $base GET "/dashboards/$($created.id)/draft" $owner.Headers
        $null = Request $base GET "/dashboards/$($created.id)" $foreign.Headers $null @(403,404)
        $null = Request $base GET "/dashboards/$($created.id)" $forged $null @(401)
    }
    $summary = @{status="passed";checks=$checks;accounts=2;gateway=$Gateway;direct=$Device;
        credentialsPersisted=$false;recordedAt=[DateTime]::UtcNow.ToString("o")}
} finally {
    foreach ($id in $dashboards) {
        $draft = Request $Dashboard GET "/dashboards/$id/draft" $owner.Headers
        $headers = $owner.Headers.Clone()
        $headers["If-Match"]='"'+$draft.etag+'"'
        $null = Request $Dashboard POST "/dashboards/$id/archive" $headers
    }
    foreach ($entry in $cleanup) {
        $null = Request $Device DELETE "/devices/$($entry.Id)" $entry.Headers $null @(200,204)
    }
}
$summary.checks=$checks
$summary.cleanup="tracked devices deleted and private drafts archived"
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root $SummaryPath)
Write-Output "User identity Docker smoke passed: $checks checks, two accounts, gateway and direct ports, scoped cleanup."
