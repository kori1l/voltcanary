param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [Parameter(Mandatory=$true)][string]$Password,
    [Parameter(Mandatory=$true)][guid]$PlanId,
    [string]$SummaryPath='artifacts/ai-task-isolation-docker-summary.json'
)
$ErrorActionPreference='Stop'
$a=Get-Content -LiteralPath $AccountPath -Raw | ConvertFrom-Json
$login=Invoke-RestMethod "$Gateway/auth/login" -Method Post -ContentType 'application/json' -Body (@{email=$a.email;password=$Password}|ConvertTo-Json)
$owner=@{Authorization="Bearer $($login.accessToken)"}
$otherEmail="ai-isolation-$([guid]::NewGuid())@example.test"
$registration=@{email=$otherEmail;password='Ai-isolation-test-2026!';displayName='Disposable AI isolation fixture'}|ConvertTo-Json
$null=Invoke-RestMethod "$Gateway/auth/register" -Method Post -ContentType 'application/json' -Body $registration
$otherLogin=Invoke-RestMethod "$Gateway/auth/login" -Method Post -ContentType 'application/json' -Body (@{email=$otherEmail;password='Ai-isolation-test-2026!'}|ConvertTo-Json)
$foreign=@{Authorization="Bearer $($otherLogin.accessToken)"}
$null=Invoke-RestMethod "$Gateway/accounts/current" -Headers $foreign
$checks=@()
function Expect-Status([string]$Name,[string]$Method,[string]$Path,$Headers,$Body,[int]$Expected) {
    try {
        $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true}
        if ($null -ne $Body) { $arguments.ContentType='application/json; charset=utf-8'; $arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 12)) }
        $response=Invoke-WebRequest @arguments
        $status=[int]$response.StatusCode
    } catch { if ($null -eq $_.Exception.Response) { throw }; $status=[int]$_.Exception.Response.StatusCode }
    if ($status -ne $Expected) { throw "$Name expected $Expected, got $status" }
    $script:checks+=@{name=$Name;status=$status;passed=$true}
}
$plan=Invoke-RestMethod "$Gateway/ai-agent/plans/$PlanId" -Headers $owner
Expect-Status 'owner status recovery' GET "/ai-agent/plans/$PlanId" $owner $null 200
Expect-Status 'foreign status hidden' GET "/ai-agent/plans/$PlanId" $foreign $null 404
Expect-Status 'foreign execution hidden' POST "/ai-agent/plans/$PlanId/execute" $foreign @{confirmed=$true;expectedVersion=$plan.version;payloadDigest=$plan.payloadDigest} 404
Expect-Status 'confirmation required' POST "/ai-agent/plans/$PlanId/execute" $owner @{confirmed=$false;expectedVersion=$plan.version;payloadDigest=$plan.payloadDigest} 409
Expect-Status 'stale reviewed version' POST "/ai-agent/plans/$PlanId/execute" $owner @{confirmed=$true;expectedVersion=999;payloadDigest=$plan.payloadDigest} 409
Expect-Status 'changed reviewed digest' POST "/ai-agent/plans/$PlanId/execute" $owner @{confirmed=$true;expectedVersion=$plan.version;payloadDigest='different'} 409
$spoof=@{Authorization=$owner.Authorization;'X-User-Id'=[guid]::NewGuid().ToString();'X-Account-Id'=[guid]::NewGuid().ToString();'X-User-Role'='ADMIN'}
Expect-Status 'forged authority headers ignored' GET "/ai-agent/plans/$PlanId" $spoof $null 200
Expect-Status 'nested credentials rejected before planning' POST '/ai-agent/plans' $owner @{message='Create device';context=@{nested=@{apiKey='disposable-fake-value'}}} 400
Expect-Status 'foreign provider rejected' POST '/ai-agent/plans' $foreign @{message='Create device';context=@{};providerId=$a.providerId;modelConfigId=$a.modelConfigId} 404
$location=Invoke-RestMethod "$Gateway/locations" -Headers $foreign -Method Post -ContentType 'application/json' -Body (@{name='Disposable foreign AI location'}|ConvertTo-Json)
try {
    Expect-Status 'foreign context rejected before provider' POST '/ai-agent/plans' $owner @{message='Create device';context=@{locationId=$location.id};providerId=$a.providerId;modelConfigId=$a.modelConfigId} 404
} finally { $null=Invoke-RestMethod "$Gateway/locations/$($location.id)" -Headers $foreign -Method Delete }
$after=Invoke-RestMethod "$Gateway/ai-agent/plans/$PlanId" -Headers $owner
if ($after.executionStatus -ne 'READY') { throw 'Rejected and cancelled requests changed task execution state' }
@{status='PASSED';planId=$PlanId;checkCount=$checks.Count;checks=$checks;recordedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host "PASSED: $($checks.Count) authenticated isolation checks"
