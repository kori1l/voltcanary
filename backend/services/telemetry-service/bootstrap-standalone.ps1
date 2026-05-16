$ErrorActionPreference = "Stop"

$serviceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Resolve-Path (Join-Path $serviceDir "..\..")

Push-Location $backendDir
try {
    mvn -pl common/common-events,common/common-web,common/common-security -am install -DskipTests
    mvn -f services/telemetry-service/pom.xml compile -DskipTests
} finally {
    Pop-Location
}

