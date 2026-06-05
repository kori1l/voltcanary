param(
    [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$violations = New-Object System.Collections.Generic.List[string]
$mainFiles = Get-ChildItem "$Root\backend\services" -Recurse -Filter *.java |
    Where-Object { $_.FullName -match "\\src\\main\\java\\" }

foreach ($file in $mainFiles) {
    $relativePath = $file.FullName.Substring($Root.Length + 1)
    $content = Get-Content -Raw $file.FullName

    if ($content -match "@RestController|@Controller") {
        if ($content -notmatch "@RestControllerAdvice|@ControllerAdvice" -and $relativePath -notmatch "\\controller\\") {
            $violations.Add("Controller outside controller package: $relativePath")
        }
    }

    if ($content -match "@Entity" -and $relativePath -notmatch "\\domain\\") {
        $violations.Add("JPA entity outside domain package: $relativePath")
    }

    if ($content -match "extends\s+(JpaRepository|CrudRepository|PagingAndSortingRepository)" -and $relativePath -notmatch "\\repository\\") {
        $violations.Add("Repository outside repository package: $relativePath")
    }

    if ($content -match "@Autowired") {
        $violations.Add("Production Spring injection annotation found: $relativePath")
    }

    if ($content -match "@Value" -and $relativePath -notmatch "\\config\\") {
        $violations.Add("Direct @Value outside config package: $relativePath")
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Project structure checks passed."
