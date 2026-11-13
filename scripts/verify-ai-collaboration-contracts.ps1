#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $root 'docs/target/ai-assistant-collaboration.schema.json'
$fixtures = Get-Content -LiteralPath (Join-Path $root 'docs/target/ai-assistant-collaboration.examples.json') -Raw | ConvertFrom-Json -Depth 100
foreach ($document in $fixtures.documents) {
    $json = ConvertTo-Json -InputObject $document -Depth 100 -Compress
    if (-not (Test-Json -Json $json -SchemaFile $schema -ErrorAction SilentlyContinue)) { throw 'Invalid AI collaboration example' }
}
foreach ($case in $fixtures.negativeCases) {
    $json = ConvertTo-Json -InputObject $case.document -Depth 100 -Compress
    if (Test-Json -Json $json -SchemaFile $schema -ErrorAction SilentlyContinue) { throw "Invalid document accepted: $($case.id)" }
}
$checker = Join-Path $PSScriptRoot 'check-ai-collaboration-examples.mjs'
$semanticJson = & node $checker --schema-cases
if ($LASTEXITCODE -ne 0) { throw 'AI documentation semantic checks failed' }
foreach ($case in ($semanticJson | ConvertFrom-Json -Depth 100)) {
    $json = ConvertTo-Json -InputObject $case.document -Depth 100 -Compress
    if (-not (Test-Json -Json $json -SchemaFile $schema -ErrorAction SilentlyContinue)) { throw "Semantic vector is not grammar-valid: $($case.id)" }
}
$metric = @{ version = 'ASSISTANT-COLLAB/1'; kind = 'metrics.evidence'; body = $fixtures.documents[1].body.payload }
if (-not (Test-Json -Json ($metric | ConvertTo-Json -Depth 100 -Compress) -SchemaFile $schema -ErrorAction SilentlyContinue)) { throw 'Invalid metric result payload example' }
& node $checker
if ($LASTEXITCODE -ne 0) { throw 'AI documentation semantic checks failed' }
Write-Output "AI documentation: $($fixtures.documents.Count) positive, $($fixtures.negativeCases.Count) negative grammar, $($fixtures.semanticCases.Count) semantic vectors passed. Not runtime or model-quality acceptance."
