param([string]$Gateway = 'http://127.0.0.1:8080', [string]$SummaryPath = 'artifacts/dashboard-domain-smoke-summary.json')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$taskClient = [System.Net.Http.HttpClient]::new()
$taskClient.Timeout = [TimeSpan]::FromSeconds(15)

function Invoke-DashboardSmokeApi($Method, $Path, $Body, $Headers, $Expected) {
    $taskRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$Gateway$Path")
    if ($Body -ne $null) {
        $taskRequest.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30 -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    foreach ($taskKey in $Headers.Keys) { $taskRequest.Headers.Add($taskKey, [string]$Headers[$taskKey]) }
    $taskResponse = $taskClient.SendAsync($taskRequest).GetAwaiter().GetResult()
    try {
        if ([int]$taskResponse.StatusCode -ne $Expected) {
            throw "Smoke request $Method $Path returned $([int]$taskResponse.StatusCode); expected $Expected."
        }
        $taskText = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $taskPayload = if ($taskText) { $taskText | ConvertFrom-Json } else { $null }
        $taskEtag = if ($taskResponse.Headers.ETag) { $taskResponse.Headers.ETag.ToString() } else { $null }
        return [pscustomobject]@{ Body = $taskPayload; ETag = $taskEtag }
    } finally { $taskResponse.Dispose(); $taskRequest.Dispose() }
}

function New-DashboardSmokeSession {
    $taskEmail = "dashboard-smoke-$([Guid]::NewGuid().ToString('N'))@example.invalid"
    $taskPassword = "Vc8!$([Guid]::NewGuid().ToString('N'))"
    $taskCredential = @{ email = $taskEmail; password = $taskPassword }
    $taskRegistered = Invoke-DashboardSmokeApi 'POST' '/auth/register' $taskCredential @{} 200
    $taskLogin = Invoke-DashboardSmokeApi 'POST' '/auth/login' $taskCredential @{} 200
    return @{ Authorization = "Bearer $($taskLogin.Body.accessToken)" }
}

try {
    $taskOwner = New-DashboardSmokeSession
    $taskOther = New-DashboardSmokeSession
    $taskKey = [Guid]::NewGuid().ToString()
    $taskDefinition = @{ schemaVersion = 1; name = 'Local private dashboard smoke'; description = ''; defaultContext = @{}; sections = @(); widgets = @(); layouts = @{} }
    $taskCreateBody = @{ definition = $taskDefinition; ownerUserId = [Guid]::NewGuid().ToString(); accountId = [Guid]::NewGuid().ToString() }
    $taskCreateHeaders = @{} + $taskOwner + @{ 'Idempotency-Key' = $taskKey }
    $taskCreated = Invoke-DashboardSmokeApi 'POST' '/dashboards' $taskCreateBody $taskCreateHeaders 201
    $taskId = $taskCreated.Body.id
    $taskReplay = Invoke-DashboardSmokeApi 'POST' '/dashboards' $taskCreateBody $taskCreateHeaders 201
    if ($taskReplay.Body.id -ne $taskId) { throw 'Create replay changed dashboard ID.' }
    $taskDraft = Invoke-DashboardSmokeApi 'GET' "/dashboards/$taskId/draft" $null $taskOwner 200
    $taskPublishBody = @{ draftEtag = $taskDraft.Body.etag }
    $taskPublishHeaders = @{} + $taskOwner + @{ 'If-Match' = $taskDraft.ETag; 'Idempotency-Key' = [Guid]::NewGuid().ToString() }
    $taskPublished = Invoke-DashboardSmokeApi 'POST' "/dashboards/$taskId/publish" $taskPublishBody $taskPublishHeaders 200
    $taskPublishReplay = Invoke-DashboardSmokeApi 'POST' "/dashboards/$taskId/publish" $taskPublishBody $taskPublishHeaders 200
    if ($taskPublished.Body.revision -ne 1 -or $taskPublishReplay.Body.revision -ne 1) { throw 'Publish replay appended history.' }
    $taskRead = Invoke-DashboardSmokeApi 'GET' "/dashboards/$taskId" $null $taskOwner 200
    if ($taskRead.Body.definition.name -ne $taskDefinition.name) { throw 'Published definition differs.' }
    foreach ($taskSuffix in @('', '/draft', '/revisions', '?revision=1')) {
        $taskDenied = Invoke-DashboardSmokeApi 'GET' "/dashboards/$taskId$taskSuffix" $null $taskOther 404
    }
    $taskStale = Invoke-DashboardSmokeApi 'PUT' "/dashboards/$taskId/draft" @{ definition = $taskDefinition; baseRevision = 0 } (@{} + $taskOwner + @{ 'If-Match' = $taskDraft.ETag }) 409
    $taskPrefs = Invoke-DashboardSmokeApi 'GET' '/dashboard-preferences/me' $null $taskOwner 200
    $taskSavedPrefs = Invoke-DashboardSmokeApi 'PUT' '/dashboard-preferences/me' @{ defaultDashboardId = $taskId; favorites = @($taskId); defaultContext = @{} } (@{} + $taskOwner + @{ 'If-Match' = $taskPrefs.ETag }) 200
    $taskDraft = Invoke-DashboardSmokeApi 'GET' "/dashboards/$taskId/draft" $null $taskOwner 200
    $taskArchived = Invoke-DashboardSmokeApi 'POST' "/dashboards/$taskId/archive" $null (@{} + $taskOwner + @{ 'If-Match' = $taskDraft.ETag }) 200
    $taskPrefs = Invoke-DashboardSmokeApi 'GET' '/dashboard-preferences/me' $null $taskOwner 200
    if ($taskPrefs.Body.defaultDashboardId -or $taskPrefs.Body.fallbackReason -ne 'DEFAULT_ARCHIVED') { throw 'Archived default was not cleared.' }
    $taskDraft = Invoke-DashboardSmokeApi 'GET' "/dashboards/$taskId/draft" $null $taskOwner 200
    $taskRestored = Invoke-DashboardSmokeApi 'POST' "/dashboards/$taskId/restore" $null (@{} + $taskOwner + @{ 'If-Match' = $taskDraft.ETag }) 200
    if ($taskRestored.Body.id -ne $taskId) { throw 'Restore changed identity.' }
    $taskUnauthenticated = Invoke-DashboardSmokeApi 'GET' '/dashboards' $null @{} 401
    $taskSummary = @{ status = 'passed'; dashboardId = $taskId; revision = 1; createReplay = 'same-id'; publishReplay = 'same-revision'; foreignReads = 4; staleDraft = 409; archivedDefault = 'cleared'; restore = 'same-id'; checkedAt = [DateTime]::UtcNow.ToString('o') }
    $taskSummary | ConvertTo-Json | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Output 'Dashboard domain smoke passed (credentials and tokens were not saved).'
} finally { $taskClient.Dispose() }
