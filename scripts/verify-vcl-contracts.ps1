#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $root 'docs/target/configurable-operations-language.schema.json'
$examples = Get-Content -LiteralPath (Join-Path $root 'docs/target/configurable-operations-language.examples.json') -Raw | ConvertFrom-Json -Depth 100
$count = 0
foreach ($document in $examples.documents) {
    $json = ConvertTo-Json -InputObject $document -Depth 100 -Compress
    if (-not (Test-Json -Json $json -SchemaFile $schema -ErrorAction SilentlyContinue)) {
        throw "Invalid positive grammar example at index $count"
    }
    $count++
}
$checker = Join-Path $PSScriptRoot 'check-vcl-document-examples.mjs'
$caseJson = & node $checker --schema-cases
if ($LASTEXITCODE -ne 0) { throw 'VCL documentation semantic checks failed' }
$cases = $caseJson | ConvertFrom-Json -Depth 100
$grammarCases = 0
foreach ($case in $cases) {
    $json = ConvertTo-Json -InputObject $case.document -Depth 100 -Compress
    $valid = Test-Json -Json $json -SchemaFile $schema -ErrorAction SilentlyContinue
    $expected = $case.stage -ne 'GRAMMAR'
    if ($valid -ne $expected) { throw "Unexpected grammar result for $($case.id): $valid" }
    if (-not $expected) { $grammarCases++ }
}
& node $checker
if ($LASTEXITCODE -ne 0) { throw 'VCL documentation semantic checks failed' }
Write-Output "VCL documentation: $count positive grammar examples, $grammarCases grammar rejections; semantic cases remain grammar-valid. Not backend/runtime acceptance."
