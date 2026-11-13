param(
    [string]$Gateway = 'http://localhost:8080',
    [string]$FixtureBaseUrl = 'http://voltcanary-ai-trace-fixture:8127/v1',
    [switch]$RestartService,
    [string]$SummaryPath = 'artifacts/ai-conversation-history-docker-summary.json'
)
$ErrorActionPreference = 'Stop'
function Api($method, $path, $body, $headers) {
    $taskParameters = @{Uri="$Gateway$path";Method=$method;Headers=$headers;ContentType='application/json';TimeoutSec=120}
    if ($null -ne $body) { $taskParameters.Body = $body | ConvertTo-Json -Depth 30 -Compress }
    Invoke-RestMethod @taskParameters
}
function Require($condition, $message) { if (-not $condition) { throw $message } }
function Wait-ConversationService {
    $taskDeadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        try { $taskHealth=Invoke-RestMethod 'http://localhost:8088/actuator/health' -TimeoutSec 10; if ($taskHealth.status -eq 'UP') {return} } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $taskDeadline)
    throw 'AI conversation service did not become healthy'
}
function FixtureOwner {
    $taskEmail = 'chat-history-' + [guid]::NewGuid().ToString('N') + '@example.test'
    $taskPassword = 'History!' + [guid]::NewGuid().ToString('N')
    $null = Api POST '/auth/register' @{email=$taskEmail;password=$taskPassword} @{}
    $taskLogin = Api POST '/auth/login' @{email=$taskEmail;password=$taskPassword} @{}
    $taskHeaders = @{Authorization="Bearer $($taskLogin.accessToken)"}
    $null = Api GET '/accounts/current' $null $taskHeaders
    $taskHeaders
}
Wait-ConversationService
$taskOwner = FixtureOwner
$null = Api POST '/ai-agent/model-providers' @{name='Isolated history fixture';providerType='OPENAI_COMPATIBLE';baseUrl=$FixtureBaseUrl;apiKey='fixture-local-key';modelId='history-fixture';makeDefault=$true;supportsJson=$true;timeoutMs=10000} $taskOwner
$taskOther = FixtureOwner
$taskId = [guid]::NewGuid().ToString()
$null = Api POST '/ai-agent/conversations' @{conversationId=$taskId} $taskOwner
$taskRequest = @{requestId=[guid]::NewGuid().ToString();expectedRevision=0;text='Check bus22 chat history. password=history-secret-probe';context=@{};automatic=$false}
$taskPrepared = Api POST "/ai-agent/conversations/$taskId/messages" $taskRequest $taskOwner
Require ($taskPrepared.pendingStatus -eq 'COMPLETED') 'Preparation did not complete'
$taskBefore = Api GET "/ai-agent/conversations/$taskId/messages" $null $taskOwner
Require ($taskBefore.items.Count -eq 2) 'Expected exactly user input and assistant reply'
Require (-not (($taskBefore|ConvertTo-Json -Depth 30).Contains('history-secret-probe'))) 'Unredacted credential persisted'
Require $taskBefore.items[0].redacted 'Expected visible redaction provenance'
$null = Api POST "/ai-agent/conversations/$taskId/messages" $taskRequest $taskOwner
$taskReplay = Api GET "/ai-agent/conversations/$taskId/messages" $null $taskOwner
Require (($taskReplay.items.id -join ',') -eq ($taskBefore.items.id -join ',')) 'Duplicate request changed message identities'
$taskFirst = Api GET "/ai-agent/conversations/$taskId/messages?limit=1" $null $taskOwner
$taskSecond = Api GET "/ai-agent/conversations/$taskId/messages?limit=1&after=$($taskFirst.nextCursor)" $null $taskOwner
Require ($taskSecond.items[0].id -eq $taskBefore.items[1].id) 'Pagination changed order'
try {
    $null = Api POST "/ai-agent/conversations/$taskId/messages" @{requestId=[guid]::NewGuid().ToString();expectedRevision=0;text='Outdated tab'} $taskOwner
    throw 'Stale revision unexpectedly accepted'
} catch { Require ([int]$_.Exception.Response.StatusCode -eq 409) 'Expected revision conflict' }
try {
    $null = Api GET "/ai-agent/conversations/$taskId" $null $taskOther
    throw 'Cross-account history unexpectedly accessible'
} catch { Require ([int]$_.Exception.Response.StatusCode -eq 404) 'Expected cross-account denial' }
if ($RestartService) {
    docker compose restart ai-agent-service | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Service restart failed' }
    Wait-ConversationService
}
$taskAfter = Api GET "/ai-agent/conversations/$taskId/messages" $null $taskOwner
$taskHistory = Api GET '/ai-agent/conversations' $null $taskOwner
Require (($taskAfter.items.id -join ',') -eq ($taskBefore.items.id -join ',')) 'Restart changed message IDs/order'
Require ($taskHistory.activeConversationId -eq $taskId) 'Selected conversation was not restored'
$taskSummary = @{checkedAt=[DateTime]::UtcNow.ToString('o');conversationId=$taskId;messageCount=$taskAfter.items.Count;
    sameMessageIds=$true;duplicateDelivery=$true;pagination=$true;revisionConflict=$true;crossAccountDenied=$true;
    redaction=$true;restart=[bool]$RestartService;executionInvocations=0}
$taskSummary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
$taskSummary | ConvertTo-Json -Depth 10
