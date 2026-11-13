param(
    [string]$Gateway = 'http://localhost:8080',
    [string]$AccountPath = 'artifacts/ai-evaluation-account.json',
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$SummaryPath = 'artifacts/ai-device-model-evaluation.json'
)
$ErrorActionPreference = 'Stop'
$account = Get-Content -LiteralPath $AccountPath -Raw | ConvertFrom-Json
$fixturePath = 'backend/services/ai-agent-service/src/test/resources/evaluation/device-intent-ru-v1.json'
$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
$login = Invoke-RestMethod "$Gateway/auth/login" -Method Post -ContentType 'application/json' -Body (@{ email=$account.email; password=$Password } | ConvertTo-Json)
$headers = @{ Authorization="Bearer $($login.accessToken)" }
$devicesBefore = Invoke-RestMethod "$Gateway/devices" -Headers $headers
$before = @($devicesBefore).Count
$outcomes = @()
function Read-Utf8Plan($Uri, $Json) {
    $response = Invoke-WebRequest $Uri -UseBasicParsing -Headers $headers -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($Json)) -TimeoutSec 90
    return ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json)
}
foreach ($case in $fixture.cases) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $body = @{ message=$case.message; context=@{}; providerId=$account.providerId; modelConfigId=$account.modelConfigId } | ConvertTo-Json -Depth 12
        $plan = Read-Utf8Plan "$Gateway/ai-agent/plans" $body
        $actions = @($plan.actions)
        $correct = $false
        if ($case.group -eq 'clear' -and $actions.Count -eq 1) {
            $action = $actions[0]
            $unexpected = @($action.parameters.PSObject.Properties.Name | Where-Object { $_ -notin $fixture.allowedFields })
            $correct = $action.tool -eq $case.expected.tool -and $action.parameters.name -ceq $case.expected.name -and $action.parameters.type -eq $case.expected.type -and $unexpected.Count -eq 0 -and !$action.parameters.locationId -and !$action.parameters.manufacturer -and !$action.parameters.model -and !$plan.fallbackUsed -and $plan.modelId -eq $account.modelId
        } elseif ($case.group -ne 'clear') { $correct = $actions.Count -eq 0 -and $plan.planningStatus -eq 'NEEDS_INPUT' }
        $providerFailed = @($plan.safetyNotes | Where-Object { $_ -match 'Provider request failed' }).Count -gt 0
        if ($providerFailed) { $correct = $false }
        $outcomes += @{ id=$case.id; group=$case.group; correct=$correct; planId=$plan.planId; status=$plan.planningStatus; actionCount=$actions.Count; modelId=$plan.modelId; fallbackUsed=$plan.fallbackUsed; latencyMs=$watch.ElapsedMilliseconds; explanation=$plan.explanation }
    } catch {
        # Never serialize HTTP exception bodies, headers or provider credentials.
        $outcomes += @{ id=$case.id; group=$case.group; correct=$false; status='REQUEST_FAILED'; latencyMs=$watch.ElapsedMilliseconds; errorType=$_.Exception.GetType().Name }
    }
    Write-Host "$($case.id): $($outcomes[-1].correct) ($($watch.ElapsedMilliseconds) ms)"
    Start-Sleep -Seconds 5
}
$devicesAfter = Invoke-RestMethod "$Gateway/devices" -Headers $headers
$after = @($devicesAfter).Count
$clearPassed = @($outcomes | Where-Object { $_.group -eq 'clear' -and $_.correct }).Count
$blockedPassed = @($outcomes | Where-Object { $_.group -ne 'clear' -and $_.correct }).Count
$passed = $clearPassed -ge 18 -and $blockedPassed -eq 20 -and $before -eq $after
@{ status=$(if ($passed) {'PASSED'} else {'FAILED'}); fixtureVersion=$fixture.version; fixtureSha256=(Get-FileHash $fixturePath -Algorithm SHA256).Hash; promptVersion=$fixture.promptVersion; schemaVersion=$fixture.schemaVersion; provider='GEMINI'; modelId=$account.modelId; clearPassed=$clearPassed; clearTotal=20; unresolvedBlocked=$blockedPassed; unresolvedTotal=20; preparationCreatedDevices=($after-$before); recordedAt=[DateTime]::UtcNow.ToString('o'); outcomes=$outcomes } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $SummaryPath -Encoding utf8
if (!$passed) { throw "Model acceptance failed: clear=$clearPassed/20; unresolved=$blockedPassed/20; see $SummaryPath" }
