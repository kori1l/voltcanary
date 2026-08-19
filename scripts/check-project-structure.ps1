param(
    [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$violations = New-Object System.Collections.Generic.List[string]
$riskReports = New-Object System.Collections.Generic.List[string]
$javaServiceLineThreshold = 350
$javaConstructorDependencyThreshold = 8
$frontendPageLineThreshold = 600
$pythonServiceModuleLineThreshold = 350

function Get-LineCount([string]$Path) {
    return (Get-Content -Path $Path | Measure-Object -Line).Lines
}

function Get-RelativePath([System.IO.FileSystemInfo]$Item) {
    return $Item.FullName.Substring($Root.Length + 1)
}

function Get-ConstructorDependencyCount([string]$Content, [string]$ClassName) {
    $match = [regex]::Match($Content, "public\s+$ClassName\s*\((?<params>[\s\S]*?)\)\s*\{")
    if (-not $match.Success) {
        return 0
    }

    $parameters = $match.Groups["params"].Value.Trim()
    if ($parameters.Length -eq 0) {
        return 0
    }

    return ([regex]::Matches($parameters, ",").Count + 1)
}

$mainFiles = Get-ChildItem "$Root\backend\services" -Recurse -Filter *.java |
    Where-Object { $_.FullName -match "\\src\\main\\java\\" }

foreach ($file in $mainFiles) {
    $relativePath = Get-RelativePath $file
    $content = Get-Content -Raw $file.FullName

    if ($content -match "(?m)^\s*package\s+([a-zA-Z0-9_.]+)\s*;") {
        $packagePath = $Matches[1].Replace(".", "\")
        $expectedSuffix = "src\main\java\$packagePath\$($file.Name)"

        if (-not $relativePath.EndsWith($expectedSuffix)) {
            $violations.Add("Package declaration does not match path: $relativePath")
        }
    }

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

    if ($relativePath -match "\\service\\" -and $file.Name -match "(ConfigService|SettingsService)\.java$") {
        $violations.Add("Generic configuration/settings service name in service package: $relativePath")
    }

    if ($content -match "(?m)^\s*import\s+.+\.\*;") {
        $riskReports.Add("Wildcard import in production Java file: $relativePath")
    }

    if ($relativePath -match "\\service\\" -and $content -match "@Service") {
        $lineCount = Get-LineCount $file.FullName
        if ($lineCount -gt $javaServiceLineThreshold) {
            $riskReports.Add("Oversized Java service class ($lineCount lines > $javaServiceLineThreshold): $relativePath")
        }

        $className = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $dependencyCount = Get-ConstructorDependencyCount $content $className
        if ($dependencyCount -gt $javaConstructorDependencyThreshold) {
            $riskReports.Add("Large Java service constructor ($dependencyCount dependencies > $javaConstructorDependencyThreshold): $relativePath")
        }
    }
}

$emptySourcePackageDirs = Get-ChildItem "$Root\backend\services" -Recurse -Directory |
    Where-Object {
        $_.FullName -match "\\src\\main\\java\\" -and
        (Get-ChildItem $_.FullName -Recurse -File | Measure-Object).Count -eq 0
    }

foreach ($dir in $emptySourcePackageDirs) {
    $relativePath = Get-RelativePath $dir
    $violations.Add("Empty source package directory: $relativePath")
}

$sourcePackagePlaceholders = Get-ChildItem "$Root\backend\services" -Recurse -Force -Filter .gitkeep |
    Where-Object { $_.FullName -match "\\src\\main\\java\\" }

foreach ($file in $sourcePackagePlaceholders) {
    $relativePath = Get-RelativePath $file
    $violations.Add("Source package placeholder file found: $relativePath")
}

$frontendPageFiles = Get-ChildItem "$Root\frontend\src" -Recurse -File -Filter *Page.tsx -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch "\.test\.tsx$" }

foreach ($file in $frontendPageFiles) {
    $lineCount = Get-LineCount $file.FullName
    if ($lineCount -gt $frontendPageLineThreshold) {
        $riskReports.Add("Oversized frontend page ($lineCount lines > $frontendPageLineThreshold): $(Get-RelativePath $file)")
    }
}

$pythonServiceModules = Get-ChildItem "$Root\ml-service\app\services" -Recurse -File -Filter *.py -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch "\\tests\\" }

foreach ($file in $pythonServiceModules) {
    $lineCount = Get-LineCount $file.FullName
    if ($lineCount -gt $pythonServiceModuleLineThreshold) {
        $riskReports.Add("Oversized Python service module ($lineCount lines > $pythonServiceModuleLineThreshold): $(Get-RelativePath $file)")
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

if ($riskReports.Count -gt 0) {
    Write-Warning "Project structure risk report:"
    $riskReports | Sort-Object | ForEach-Object { Write-Warning $_ }
}

Write-Host "Project structure checks passed."
