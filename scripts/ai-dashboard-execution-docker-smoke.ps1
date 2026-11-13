param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [string]$EvaluationPath='artifacts/ai-dashboard-model-evaluation.json',
    [Parameter(Mandatory=$true)][string]$Password,
    [switch]$InjectResponseLoss,
    [string]$SummaryPath='artifacts/ai-dashboard-execution-docker-summary.json'
)
$ErrorActionPreference='Stop'
function Api($Method,$Path,$Body,$Headers) {
    $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true;TimeoutSec=90}
    if ($null -ne $Body) {$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 40))}
    $response=Invoke-WebRequest @arguments
    if ($response.StatusCode -ne 204 -and $response.RawContentStream.Length -gt 0) {return ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())|ConvertFrom-Json)}
}
function Expect($Name,$Method,$Path,$Body,$Headers,$Status) {
    $actual=200
    try {$null=Api $Method $Path $Body $Headers} catch {if (!$_.Exception.Response){throw};$actual=[int]$_.Exception.Response.StatusCode}
    if ($actual -ne $Status) {throw "$Name expected $Status, got $actual"}
    $script:checks+=@{name=$Name;passed=$true;status=$actual}
}
$a=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
$evaluation=Get-Content -LiteralPath $EvaluationPath -Raw -Encoding UTF8|ConvertFrom-Json
if ($evaluation.status -ne 'PASSED') {throw 'A passed frozen model evaluation is required'}
$login=Api POST '/auth/login' @{email=$a.email;password=$Password} @{}
$owner=@{Authorization="Bearer $($login.accessToken)"}
$ids=@()
foreach($outcome in @($evaluation.outcomes|Where-Object {$_.group -eq 'clear' -and $_.correct})) {
    $candidate=Api GET "/ai-agent/plans/$($outcome.planId)" $null $owner
    if ($candidate.executionStatus -ne 'READY' -or $candidate.actions.Count -ne 1 -or $candidate.actions[0].tool -ne 'CREATE_DASHBOARD') {continue}
    if ($ids.Count -eq 0 -and $candidate.actions[0].parameters.templateId -ne 'my-devices') {continue}
    $ids+=([guid]$candidate.planId).ToString()
    if ($ids.Count -eq 2) {break}
}
if ($ids.Count -ne 2) {throw 'Two fresh reviewed fixtures, starting with My devices, are required'}
$otherEmail="ai-dashboard-isolation-$([guid]::NewGuid())@example.test"
$otherPassword='Ai-dashboard-isolation-2026!'
$null=Api POST '/auth/register' @{email=$otherEmail;password=$otherPassword;displayName='Disposable dashboard access fixture'} @{}
$other=Api POST '/auth/login' @{email=$otherEmail;password=$otherPassword} @{}
$foreign=@{Authorization="Bearer $($other.accessToken)"}
$null=Api GET '/accounts/current' $null $foreign
$foreignDevice=Api POST '/devices' @{name='Foreign dashboard fixture';type='FRIDGE'} $foreign
$foreignSource=Api POST "/devices/$($foreignDevice.id)/data-sources" @{sourceType='SENSOR';protocol='REST';externalId="ai-foreign-$([guid]::NewGuid())"} $foreign
$checks=@();$jobs=@();$created=@();$recovered=$null
$preferencesBefore=Api GET '/dashboard-preferences/me' $null $owner
try {
    $plan=Api GET "/ai-agent/plans/$($ids[0])" $null $owner
    if ($plan.executionStatus -ne 'READY' -or $plan.actions.Count -ne 1 -or $plan.actions[0].tool -ne 'CREATE_DASHBOARD') {throw 'Fresh single dashboard plan required'}
    $intent=$plan.actions[0].parameters.prepareIntent
    $template=$plan.actions[0].parameters.templateId
    $mixed=@{version=$intent.version;name='Mixed forbidden';deviceIds=@($intent.deviceIds[0],$foreignDevice.id);period=$intent.period}
    Expect 'mixed foreign binding denied' POST "/dashboard-templates/$template/prepare" $mixed $owner 403
    $sourceAttack=@{version=$intent.version;name='Foreign source forbidden';deviceIds=$intent.deviceIds;period=$intent.period;sourceIds=@($foreignSource.id)}
    Expect 'foreign source denied' POST "/dashboard-templates/$template/prepare" $sourceAttack $owner 403
    $scopeAttack=@{version=$intent.version;name='Forged workspace';deviceIds=$intent.deviceIds;workspaceId=[guid]::NewGuid().ToString()}
    Expect 'raw ownership field rejected' POST "/dashboard-templates/$template/prepare" $scopeAttack $owner 422
    $changed=Api POST "/dashboard-templates/$template/prepare" @{version=999;name='Changed template';deviceIds=$intent.deviceIds} $owner
    if ($changed.status -ne 'NEEDS_INPUT' -or $changed.definition) {throw 'Template drift was not blocked'}
    $checks+=@{name='template version drift asks without definition';passed=$true}
    $execute=@{confirmed=$true;expectedVersion=$plan.version;payloadDigest=$plan.payloadDigest}
    Expect 'foreign task status hidden' GET "/ai-agent/plans/$($plan.planId)" $null $foreign 404
    Expect 'foreign task execution hidden' POST "/ai-agent/plans/$($plan.planId)/execute" $execute $foreign 404
    Expect 'cancelled review does not execute' POST "/ai-agent/plans/$($plan.planId)/execute" @{confirmed=$false;expectedVersion=$plan.version;payloadDigest=$plan.payloadDigest} $owner 409
    Expect 'stale reviewed version rejected' POST "/ai-agent/plans/$($plan.planId)/execute" @{confirmed=$true;expectedVersion=999;payloadDigest=$plan.payloadDigest} $owner 409
    $json=$execute|ConvertTo-Json
    for($i=0;$i -lt 6;$i++) {
        $jobs+=Start-Job -ScriptBlock {param($Url,$Auth,$Body);$ErrorActionPreference='Stop';Invoke-RestMethod $Url -Method Post -Headers @{Authorization=$Auth} -ContentType application/json -Body $Body} -ArgumentList "$Gateway/ai-agent/plans/$($plan.planId)/execute",$owner.Authorization,$json
    }
    $null=$jobs|Wait-Job -Timeout 60
    $responses=@($jobs|Receive-Job -ErrorAction Stop)
    if ($responses.Count -ne 6 -or @($responses|Where-Object {$_.status -notin @('RUNNING','SUCCESS')}).Count) {throw 'Unsafe concurrent outcome'}
    $recorded=Api GET "/ai-agent/plans/$($plan.planId)" $null $owner
    if ($recorded.executionStatus -ne 'SUCCESS') {throw 'Success not persisted'}
    $created+=([guid]$recorded.execution.actions[0].result.dashboardId).ToString()
    $replay=Api POST "/ai-agent/plans/$($plan.planId)/execute" $execute $owner
    if ($replay.actions[0].result.dashboardId -ne $created[0]) {throw 'Replay duplicated creation'}
    $checks+=@{name='six concurrent confirmations and replay return one draft';passed=$true}
    if ($InjectResponseLoss) {
        # Fault injection is restricted to this freshly reviewed fixture, never to arbitrary or completed plans.
        $lost=Api GET "/ai-agent/plans/$($ids[1])" $null $owner
        if ($lost.executionStatus -ne 'READY' -or $lost.actions.Count -ne 1 -or $lost.actions[0].tool -ne 'CREATE_DASHBOARD') {throw 'Fresh response-loss fixture required'}
        $planId=([guid]$lost.planId).ToString();$userId=([guid]$a.userId).ToString();$accountId=([guid]$a.accountId).ToString();$actionId=([guid]$lost.actions[0].id).ToString()
        $stable="ai-$userId-$accountId-$planId-$($lost.version)-$actionId"
        $sha=[Security.Cryptography.SHA256]::Create()
        try {$digest=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($stable)))).Replace('-','').ToLowerInvariant()} finally {$sha.Dispose()}
        $receiptHeaders=@{Authorization=$owner.Authorization;'Idempotency-Key'="ai-dashboard-$digest"}
        $committed=Api POST '/dashboards' @{definition=$lost.actions[0].parameters.definition} $receiptHeaders
        $created+=([guid]$committed.id).ToString()
        # Simulate loss after the owning transaction commits; payload, owner, version and digest stay immutable.
        $sql="with target as (update ai_reviewed_plans set status='UNKNOWN',updated_at=now() where id='$planId' and user_id='$userId' and account_id='$accountId' and status='READY' and version=$($lost.version) returning id) update ai_plan_actions set status='UNKNOWN',updated_at=now() where plan_id in (select id from target) and action_id='$actionId' and status='READY' returning action_id;"
        $changedRows=& docker compose exec -T postgres psql -U voltcanary -d voltcanary_ai_agent -At -c $sql
        if ($LASTEXITCODE -ne 0 -or $actionId -notin @($changedRows)) {throw 'Scoped fixture fault injection did not affect exactly the expected action'}
        $null=& docker compose restart ai-agent-service
        if ($LASTEXITCODE -ne 0) {throw 'AI service restart failed'}
        $ready=$false
        for($attempt=0;$attempt -lt 100;$attempt++) {
            try {$health=Invoke-RestMethod 'http://localhost:8088/actuator/health' -TimeoutSec 5;if($health.status -eq 'UP'){$ready=$true;break}} catch {}
            Start-Sleep -Seconds 3
        }
        if (!$ready) {throw 'AI restart did not become healthy'}
        $unknown=Api GET "/ai-agent/plans/$planId" $null $owner
        if ($unknown.executionStatus -ne 'UNKNOWN') {throw 'Restart lost unknown outcome'}
        $recovered=Api POST "/ai-agent/plans/$planId/execute" @{confirmed=$true;expectedVersion=$lost.version;payloadDigest=$lost.payloadDigest} $owner
        if ($recovered.status -ne 'SUCCESS' -or $recovered.actions[0].result.dashboardId -ne $committed.id) {throw 'Recovery did not use the original receipt'}
        Expect 'foreign receipt hidden' GET '/dashboards/creation-receipt' $null @{Authorization=$foreign.Authorization;'Idempotency-Key'="ai-dashboard-$digest"} 404
        $checks+=@{name='response loss and process restart recover original private draft';passed=$true}
    }
    foreach($id in $created) {
        $view=Api GET "/dashboards/$id" $null $owner
        $draft=Api GET "/dashboards/$id/draft" $null $owner
        $revisions=Api GET "/dashboards/$id/revisions" $null $owner
        if ($view.dashboard.publishedRevision -ne 0 -or @($revisions.items).Count -ne 0 -or $draft.definition.defaultContext.scope.deviceIds[0] -ne $intent.deviceIds[0]) {throw 'Draft is published or has wrong bindings'}
        Expect 'foreign created draft hidden' GET "/dashboards/$id" $null $foreign 404
        if ($id -eq $created[0]) {
            $power=@($draft.definition.widgets|Where-Object {$_.type -eq 'latest-value' -and $_.query.metric -eq 'activePowerW'})
            if ($power.Count -ne 1) {throw 'The first execution fixture must include one latest-power panel'}
            $query=@{draftEtag=$draft.etag;widgetIds=@($power[0].id);forceRefresh=$true}
            $zero=Api POST "/dashboards/$id/query" $query $owner
            if ($zero.panels[0].status -ne 'ready' -or $null -eq $zero.panels[0].data.value.value -or $zero.panels[0].data.value.value -ne 0) {throw 'Accepted zero did not remain a numeric zero in ordinary widget reads'}
            # Latest is an asOf snapshot. An empty interval is tested on history through the ordinary editor API.
            $history=@($draft.definition.widgets|Where-Object {$_.type -eq 'time-series' -and $_.query.metric -eq 'activePowerW'})
            if ($history.Count -ne 1) {throw 'One measured power history panel is required'}
            $history[0].query.time=@{mode='FIXED';from=[DateTime]::UtcNow.AddHours(-2).ToString('o');to=[DateTime]::UtcNow.AddHours(-1).ToString('o');timezone='UTC'}
            $saved=Api PUT "/dashboards/$id/draft" @{baseRevision=$draft.baseRevision;definition=$draft.definition} @{Authorization=$owner.Authorization;'If-Match'=('"'+$draft.etag+'"')}
            $query=@{draftEtag=$saved.etag;widgetIds=@($history[0].id);forceRefresh=$true}
            $empty=Api POST "/dashboards/$id/query" $query $owner
            if ($empty.panels[0].status -ne 'no_data' -or @($empty.panels[0].data.series|ForEach-Object {$_.buckets}).Count -ne 0) {throw 'An empty supported history window fabricated observations'}
            $checks+=@{name='ordinary widget reads distinguish accepted zero from an empty supported window';passed=$true}
        }
    }
    $preferencesAfter=Api GET '/dashboard-preferences/me' $null $owner
    if (($preferencesBefore|ConvertTo-Json -Depth 20 -Compress) -cne ($preferencesAfter|ConvertTo-Json -Depth 20 -Compress)) {throw 'Implicit dashboard preference change'}
    $checks+=@{name='private unpublished drafts preserve personal preferences';passed=$true}
    $evidence=@{status='PASSED';checkCount=$checks.Count;checks=$checks;dashboardIds=$created;responseLossRestartTested=[bool]$InjectResponseLoss;recordedAt=[DateTime]::UtcNow.ToString('o')}
} finally {
    $jobs|Remove-Job -Force
    foreach($id in $created) {
        $draft=Api GET "/dashboards/$id/draft" $null $owner
        $null=Api POST "/dashboards/$id/archive" $null @{Authorization=$owner.Authorization;'If-Match'=('"'+$draft.etag+'"')}
    }
    $null=Api POST "/devices/$($foreignDevice.id)/decommission" $null $foreign
}
$evidence.cleanupCompleted=$true
$evidence|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host "PASSED: $($checks.Count) dashboard execution checks and scoped cleanup"
