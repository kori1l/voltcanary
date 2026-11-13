param(
    [string]$Gateway='http://localhost:8080',
    [string]$AccountPath='artifacts/ai-evaluation-account.json',
    [string]$DeviceFixturePath='artifacts/ai-dashboard-fixture.json',
    [Parameter(Mandatory=$true)][string]$Password,
    [string]$SummaryPath='artifacts/ai-dashboard-model-evaluation.json'
)
$ErrorActionPreference='Stop'
$account=Get-Content -LiteralPath $AccountPath -Raw -Encoding UTF8|ConvertFrom-Json
$device=Get-Content -LiteralPath $DeviceFixturePath -Raw -Encoding UTF8|ConvertFrom-Json
$fixturePath='backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-intent-ru-v1.json'
$fixture=Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8|ConvertFrom-Json
function Api($Method,$Path,$Body,$Headers) {
    $arguments=@{Uri="$Gateway$Path";Method=$Method;Headers=$Headers;UseBasicParsing=$true;TimeoutSec=90}
    if ($null -ne $Body) { $arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 20)) }
    $response=Invoke-WebRequest @arguments
    if ($response.StatusCode -eq 204) { return $null }
    return ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())|ConvertFrom-Json)
}
$login=Api POST '/auth/login' @{email=$account.email;password=$Password} @{}
$headers=@{Authorization="Bearer $($login.accessToken)"}
$before=Api GET '/dashboards?limit=100&status=ALL' $null $headers
if ($before.hasNext) { throw 'Use a dedicated bounded evaluation account' }
$beforeDevices=Api GET '/devices' $null $headers
$outcomes=@()
foreach ($case in $fixture.cases) {
    $timer=[Diagnostics.Stopwatch]::StartNew()
    try {
        $body=@{message=$case.message;context=@{deviceIds=@($device.deviceId)};providerId=$account.providerId;modelConfigId=$account.modelConfigId}
        $plan=Api POST '/ai-agent/plans' $body $headers
        $correct=$false
        if ($case.group -eq 'clear' -and @($plan.actions).Count -eq 1) {
            $action=$plan.actions[0];$parameters=$action.parameters
            $unknown=@($parameters.PSObject.Properties.Name|Where-Object {$_ -notin $fixture.allowedCompiledFields})
            $selected=@($parameters.prepareIntent.deviceIds)
            $correct=$action.tool -eq $case.expected.tool -and $parameters.definition.name -ceq $case.expected.name -and $parameters.templateId -eq $case.expected.templateId -and $parameters.templateVersion -eq $case.expected.templateVersion -and $parameters.definition.defaultContext.time.preset -eq $case.expected.period -and $selected.Count -eq 1 -and $selected[0] -eq $device.deviceId -and @($parameters.definition.widgets).Count -le 12 -and @($parameters.definition.widgets).Count -gt 0 -and $unknown.Count -eq 0 -and !$parameters.definition.defaultContext.scope.workspaceId -and !$parameters.definition.defaultContext.scope.accountId
        } elseif ($case.group -ne 'clear') { $correct=@($plan.actions).Count -eq 0 -and $plan.planningStatus -eq 'NEEDS_INPUT' }
        $providerFailed=@($plan.safetyNotes|Where-Object {$_ -match 'Provider request failed'}).Count -gt 0
        if ($providerFailed -or $plan.fallbackUsed -or $plan.modelId -ne $account.modelId) { $correct=$false }
        $outcomes+=@{id=$case.id;group=$case.group;correct=$correct;planId=$plan.planId;status=$plan.planningStatus;actionCount=@($plan.actions).Count;modelId=$plan.modelId;provider=$plan.providerType;providerFailed=$providerFailed;fallbackUsed=$plan.fallbackUsed;latencyMs=$timer.ElapsedMilliseconds;questions=$plan.questions}
    } catch {
        $status=0;if ($_.Exception.Response) {$status=[int]$_.Exception.Response.StatusCode}
        $outcomes+=@{id=$case.id;group=$case.group;correct=($case.group -ne 'clear' -and $status -in @(400,403,404,409,422));status='REQUEST_REJECTED';httpStatus=$status;latencyMs=$timer.ElapsedMilliseconds}
    }
    Write-Host "$($case.id): $($outcomes[-1].correct) ($($timer.ElapsedMilliseconds) ms)"
    Start-Sleep -Seconds 5
}
$after=Api GET '/dashboards?limit=100&status=ALL' $null $headers
$afterDevices=Api GET '/devices' $null $headers
$clearPassed=@($outcomes|Where-Object {$_.group -eq 'clear' -and $_.correct}).Count
$blockedPassed=@($outcomes|Where-Object {$_.group -ne 'clear' -and $_.correct}).Count
$createdDashboards=@($after.items).Count-@($before.items).Count
$createdDevices=@($afterDevices).Count-@($beforeDevices).Count
$passed=$clearPassed -ge 18 -and $blockedPassed -eq 20 -and $createdDashboards -eq 0 -and $createdDevices -eq 0
$unavailable=@($outcomes|Where-Object {$_.providerFailed -or ($_.status -eq 'REQUEST_REJECTED' -and $_.httpStatus -in @(0,500,502,503,504))}).Count
@{status=$(if($passed){'PASSED'}elseif($unavailable -eq $fixture.cases.Count){'BLOCKED'}else{'FAILED'});unavailableCount=$unavailable;fixtureVersion=$fixture.version;fixtureSha256=(Get-FileHash $fixturePath -Algorithm SHA256).Hash;promptVersion=$fixture.promptVersion;schemaVersion=$fixture.schemaVersion;templateVersion=$fixture.templateVersion;modelId=$account.modelId;clearPassed=$clearPassed;clearTotal=20;unresolvedBlocked=$blockedPassed;unresolvedTotal=20;createdDashboards=$createdDashboards;createdDevices=$createdDevices;recordedAt=[DateTime]::UtcNow.ToString('o');outcomes=$outcomes}|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $SummaryPath -Encoding utf8
if (!$passed) { throw "Dashboard model acceptance failed: clear=$clearPassed/20; unresolved=$blockedPassed/20; see $SummaryPath" }
