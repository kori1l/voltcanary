param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $ChangeId
)

$ErrorActionPreference = "Stop"

function Invoke-OpenSpec {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $OpenSpecArgs
    )

    & node .\node_modules\@fission-ai\openspec\bin\openspec.js @OpenSpecArgs
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSpec command failed: openspec $($OpenSpecArgs -join ' ')"
    }
}

function Require-Path {
    param([string] $Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required OpenSpec artifact is missing: $Path"
    }
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$changePath = Join-Path $root "openspec\changes\$ChangeId"
$archivePath = Join-Path $root "openspec\archive\$ChangeId"

Write-Host "== OpenSpec active changes =="
$listOutputFile = New-TemporaryFile
try {
    & node .\node_modules\@fission-ai\openspec\bin\openspec.js list *> $listOutputFile
    $listExitCode = $LASTEXITCODE
    $listOutput = Get-Content -LiteralPath $listOutputFile
} finally {
    Remove-Item -LiteralPath $listOutputFile -Force -ErrorAction SilentlyContinue
}
$listOutput | ForEach-Object { Write-Host $_ }
if ($listExitCode -ne 0) {
    throw "OpenSpec command failed: openspec list"
}

$escapedChangeId = [regex]::Escape($ChangeId)
$isActive = ($listOutput -join "`n") -match "(?m)^\s+$escapedChangeId\s+"
if (-not $isActive) {
    if (Test-Path -LiteralPath $archivePath) {
        throw "Change '$ChangeId' is archived, not active. Do not implement archived OpenSpec changes unless explicitly requested."
    }
    throw "Change '$ChangeId' is not active. Run 'npm run openspec:list' and choose an active change."
}

Require-Path $changePath
Require-Path (Join-Path $changePath "proposal.md")
Require-Path (Join-Path $changePath "tasks.md")

Write-Host ""
Write-Host "== OpenSpec show =="
Invoke-OpenSpec show $ChangeId --type change --no-interactive

Write-Host ""
Write-Host "== OpenSpec status =="
Invoke-OpenSpec status --change $ChangeId

Write-Host ""
Write-Host "== OpenSpec task instructions =="
Invoke-OpenSpec instructions tasks --change $ChangeId

Write-Host ""
Write-Host "== OpenSpec validation =="
Invoke-OpenSpec validate --all --strict

Write-Host ""
Write-Host "OpenSpec apply context is ready for '$ChangeId'."
Write-Host "Read and follow:"
Write-Host "  openspec/changes/$ChangeId/proposal.md"
Write-Host "  openspec/changes/$ChangeId/design.md, if present"
Write-Host "  openspec/changes/$ChangeId/tasks.md"
Write-Host "  openspec/changes/$ChangeId/specs/**/spec.md"
Write-Host "Update tasks.md only after implementation and verification."
