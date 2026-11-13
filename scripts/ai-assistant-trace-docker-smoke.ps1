param(
    [string]$Gateway = 'http://localhost:8080',
    [string]$FixtureBaseUrl = 'http://voltcanary-ai-trace-fixture:8127/v1',
    [string]$SummaryPath = 'artifacts/ai-assistant-trace-docker-summary.json'
)
$ErrorActionPreference = 'Stop'
function Api($method,$path,$body,$headers) {
    $taskParameters=@{Uri="$Gateway$path";Method=$method;Headers=$headers;ContentType='application/json';TimeoutSec=120}
    if($null -ne $body){$taskParameters.Body=$body|ConvertTo-Json -Depth 30 -Compress}
    Invoke-RestMethod @taskParameters
}
function Require($condition,$message){if(-not $condition){throw $message}}
function FixtureOwner {
    $taskCredentials=@{email=('trace-'+[guid]::NewGuid().ToString('N')+'@example.test');password=('Trace!'+[guid]::NewGuid().ToString('N'))}
    $null=Api POST '/auth/register' $taskCredentials @{}
    $taskLogin=Api POST '/auth/login' $taskCredentials @{}
    $taskHeaders=@{Authorization="Bearer $($taskLogin.accessToken)"}
    $null=Api GET '/accounts/current' $null $taskHeaders
    $taskHeaders
}
$taskDeadline=[DateTime]::UtcNow.AddMinutes(4)
do {
    try{$taskHealth=Invoke-RestMethod 'http://localhost:8088/actuator/health' -TimeoutSec 10;if($taskHealth.status -eq 'UP'){break}}catch{}
    Start-Sleep -Seconds 2
}while([DateTime]::UtcNow -lt $taskDeadline)
Require ($taskHealth.status -eq 'UP') 'AI service unavailable'
$taskOwner=FixtureOwner
$taskOther=FixtureOwner
$taskProvider=Api POST '/ai-agent/model-providers' @{name='Isolated trace fixture';providerType='OPENAI_COMPATIBLE';baseUrl=$FixtureBaseUrl;apiKey='fixture-local-key';modelId='trace-fixture';makeDefault=$true;supportsJson=$true;timeoutMs=10000} $taskOwner
$taskConversation=[guid]::NewGuid().ToString()
$null=Api POST '/ai-agent/conversations' @{conversationId=$taskConversation} $taskOwner
$taskMissing=[guid]::NewGuid().ToString()
$taskFailure=Api POST "/ai-agent/conversations/$taskConversation/messages" @{requestId=$taskMissing;expectedRevision=0;text='Create dashboard for this missing device';context=@{deviceId=[guid]::NewGuid().ToString()}} $taskOwner
Require ($taskFailure.pendingStatus -eq 'FAILED') 'Missing context was not recorded as failed'
$taskMissingEvents=Api GET "/ai-agent/conversations/$taskConversation/turns/$taskMissing/trace/export" $null $taskOwner
Require (@($taskMissingEvents|Where-Object {$_.stage -eq 'CONTEXT_READ' -and $_.code -eq 'CONTEXT_HTTP_404'}).Count -gt 0) 'Missing resource stage not diagnosable'
$taskMissingConversation=$taskConversation
$taskConversation=[guid]::NewGuid().ToString()
$null=Api POST '/ai-agent/conversations' @{conversationId=$taskConversation} $taskOwner
$taskRejected=[guid]::NewGuid().ToString()
$taskPrepared=Api POST "/ai-agent/conversations/$taskConversation/messages" @{requestId=$taskRejected;expectedRevision=0;text='Create one device named trace-fixture-bus';context=@{};providerId=$taskProvider.id} $taskOwner
$taskRejectedEvents=Api GET "/ai-agent/conversations/$taskConversation/turns/$taskRejected/trace/export" $null $taskOwner
$taskChecks=@($taskRejectedEvents|Where-Object {$_.stage -eq 'MODEL_CALL' -and $_.code -eq 'MODEL_PROPOSAL_CONTRACT_INVALID'})
Require ($taskChecks.Count -gt 0) ("Rejected-field check not captured; codes=" + (($taskRejectedEvents | Where-Object {$_.code} | ForEach-Object {$_.code}) -join ','))
$taskAttempts=@($taskRejectedEvents|Where-Object {$_.stage -eq 'MODEL_CALL' -and $_.status -eq 'STARTED'}).Count
Require ($taskAttempts -ge 1 -and $taskAttempts -le 2) 'Expected bounded primary/correction attempts'
$taskCompletions=@($taskRejectedEvents|Where-Object {$_.stage -eq 'PROVIDER_USAGE'}).Count
Require ($taskCompletions -eq $taskAttempts) 'Every completed fixture response must have its own attempt-correlated usage event'
Require ($taskPrepared.plan.planningStatus -ne 'READY') 'Rejected candidate incorrectly became READY'
$taskExport=$taskRejectedEvents|ConvertTo-Json -Depth 40
Require (-not $taskExport.Contains('trace-fixture-secret-must-not-persist')) 'Fixture secret leaked'
Require (-not $taskExport.Contains('private-fixture-reasoning-must-not-persist')) 'Private reasoning leaked'
$taskPage=Api GET "/ai-agent/conversations/$taskConversation/turns/$taskRejected/trace?limit=2" $null $taskOwner
Require ($taskPage.items.Count -eq 2 -and $taskPage.nextCursor) 'Trace pagination unavailable'
try {
    $null=Api GET "/ai-agent/conversations/$taskConversation/turns/$taskRejected/trace/export" $null $taskOther
    throw 'Cross-account export unexpectedly accepted'
}catch{Require ([int]$_.Exception.Response.StatusCode -eq 404) 'Expected cross-account trace denial'}
$taskSummary=@{checkedAt=[DateTime]::UtcNow.ToString('o');conversationId=$taskConversation;missingContextTurn=$taskMissing;rejectedFieldTurn=$taskRejected;
    missingContextDiagnosed=$true;contractRejected=$true;logicalAttempts=$taskAttempts;observedProviderCompletions=$taskCompletions;rejectedReviewReady=$false;
    secretsExcluded=$true;privateReasoningExcluded=$true;crossAccountDenied=$true;paginated=$true;executionInvocations=0;
    provider='deterministic isolated HTTP fixture; not model-quality evidence'}
$taskSummary|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $SummaryPath -Encoding UTF8
$taskRejectedEvents|ConvertTo-Json -Depth 40|Set-Content artifacts/ai-assistant-rejected-field-trace.json -Encoding UTF8
$taskMissingEvents|ConvertTo-Json -Depth 40|Set-Content artifacts/ai-assistant-missing-context-trace.json -Encoding UTF8
$taskSummary|ConvertTo-Json -Depth 12
