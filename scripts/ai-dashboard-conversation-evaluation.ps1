param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [Parameter(Mandatory = $true)][string]$AccountPath,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [ValidateRange(1, 10)][int]$Trials = 2,
    [ValidateSet(1, 2)][int]$Workers = 1,
    [string]$Cases,
    [switch]$ObserveDisposableProvider
)
$ErrorActionPreference = 'Stop'
$arguments = @(
    (Join-Path $PSScriptRoot 'ai-dashboard-conversation-evaluation.py'),
    '--gateway', $Gateway, '--account', $AccountPath, '--output', $OutputPath,
    '--trials', $Trials, '--workers', $Workers
)
if ($Cases) { $arguments += @('--cases', $Cases) }
if ($ObserveDisposableProvider) { $arguments += '--observe-disposable-provider' }
$env:PYTHONUTF8 = '1'
# Credentials are inherited from VOLT_EVAL_PASSWORD or VOLT_EVAL_ACCESS_TOKEN,
# never accepted as command-line values or written to fixture metadata.
& python @arguments
exit $LASTEXITCODE
