param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [Parameter(Mandatory=$true)][string]$Password,
    [Parameter(Mandatory=$true)][guid]$PlanId,
    [string]$SummaryPath='artifacts/ai-device-execution-docker-summary.json'
)
$ErrorActionPreference='Stop'
$a=Get-Content -LiteralPath $AccountPath -Raw | ConvertFrom-Json
$login=Invoke-RestMethod "$Gateway/auth/login" -Method Post -ContentType 'application/json' -Body (@{email=$a.email;password=$Password}|ConvertTo-Json)
$headers=@{Authorization="Bearer $($login.accessToken)"}
$plan=Invoke-RestMethod "$Gateway/ai-agent/plans/$PlanId" -Headers $headers
if ($plan.actions.Count -ne 1 -or $plan.actions[0].tool -ne 'CREATE_DEVICE') { throw 'A single reviewed CREATE_DEVICE fixture is required' }
$body=@{confirmed=$true;expectedVersion=$plan.version;payloadDigest=$plan.payloadDigest}|ConvertTo-Json
$jobs=@()
try {
    for ($i=0;$i -lt 6;$i++) {
        $jobs+=Start-Job -ScriptBlock {
            param($Uri,$Auth,$Json)
            $ErrorActionPreference='Stop'
            Invoke-RestMethod $Uri -Headers @{Authorization=$Auth} -Method Post -ContentType 'application/json' -Body $Json
        } -ArgumentList "$Gateway/ai-agent/plans/$PlanId/execute",$headers.Authorization,$body
    }
    $null=$jobs|Wait-Job -Timeout 45
    $responses=@($jobs|Receive-Job -ErrorAction Stop)
    if ($responses.Count -ne 6 -or @($responses|Where-Object {$_.status -notin @('RUNNING','SUCCESS')}).Count -gt 0) { throw 'Concurrent execution did not return safe recorded outcomes' }
    $recorded=Invoke-RestMethod "$Gateway/ai-agent/plans/$PlanId" -Headers $headers
    if ($recorded.executionStatus -ne 'SUCCESS') { throw 'Execution did not persist SUCCESS' }
    $deviceId=$recorded.execution.actions[0].result.deviceId
    $devices=Invoke-RestMethod "$Gateway/devices" -Headers $headers
    if (@($devices|Where-Object {$_.id -eq $deviceId}).Count -ne 1) { throw 'Actual created resource missing' }
    $sources=Invoke-RestMethod "$Gateway/devices/$deviceId/data-sources" -Headers $headers
    if (@($sources).Count -ne 0) { throw 'Real device creation implicitly created a source' }
    $repeat=Invoke-RestMethod "$Gateway/ai-agent/plans/$PlanId/execute" -Headers $headers -Method Post -ContentType 'application/json' -Body $body
    if ($repeat.actions[0].result.deviceId -ne $deviceId) { throw 'Replay changed created resource identity' }
    @{status='PASSED';planId=$PlanId;deviceId=$deviceId;parallelRequests=6;sourceCount=0;replaySameDevice=$true;recordedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath $SummaryPath -Encoding utf8
    Write-Host 'PASSED: concurrent execute, persisted result, stable replay and no implicit source'
} finally { $jobs|Remove-Job -Force }
