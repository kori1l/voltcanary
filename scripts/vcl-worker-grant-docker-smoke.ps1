param(
    [string]$SummaryPath = 'artifacts/g4-worker-grant-docker-summary-20261107.json'
)

$ErrorActionPreference = 'Stop'
$hadKey = Test-Path Env:APP_VCL_WORKER_HMAC_KEY
$originalKey = if ($hadKey) { $env:APP_VCL_WORKER_HMAC_KEY } else { $null }
$hadKeyId = Test-Path Env:APP_VCL_WORKER_KEY_ID
$originalKeyId = if ($hadKeyId) { $env:APP_VCL_WORKER_KEY_ID } else { $null }
$failed = $false
$changed = $false

function Wait-OwnerHealth {
    param([int]$Port)
    $deadline = (Get-Date).AddMinutes(10)
    do {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "OWNER_NOT_READY_$Port"
}

try {
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = [byte[]]::new(32)
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $env:APP_VCL_WORKER_HMAC_KEY = [Convert]::ToBase64String($bytes)
    $env:APP_VCL_WORKER_KEY_ID = 'worker-1'
    $changed = $true
    docker compose --profile app up -d --no-deps --force-recreate device-service alert-service | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'OWNER_RESTART_FAILED' }
    Wait-OwnerHealth 8082
    Wait-OwnerHealth 8086
    # Recreated owners may have new IPs while the existing gateway retains an old route connection/address.
    docker compose restart api-gateway | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'GATEWAY_RESTART_FAILED' }
    Wait-OwnerHealth 8080
    python scripts/configurable-owner-docker-smoke.py --summary $SummaryPath
    if ($LASTEXITCODE -ne 0) { throw 'MACHINE_GRANT_SMOKE_FAILED' }
} catch {
    Write-Output "MACHINE_GRANT_SMOKE_FAILED: $($_.Exception.Message)"
    $failed = $true
} finally {
    if ($hadKey) { $env:APP_VCL_WORKER_HMAC_KEY = $originalKey }
    else { Remove-Item Env:APP_VCL_WORKER_HMAC_KEY -ErrorAction SilentlyContinue }
    if ($hadKeyId) { $env:APP_VCL_WORKER_KEY_ID = $originalKeyId }
    else { Remove-Item Env:APP_VCL_WORKER_KEY_ID -ErrorAction SilentlyContinue }
    if ($changed) {
        docker compose --profile app up -d --no-deps --force-recreate device-service alert-service | Out-Null
        if ($LASTEXITCODE -ne 0) { $failed = $true }
        try {
            Wait-OwnerHealth 8082
            Wait-OwnerHealth 8086
            docker compose restart api-gateway | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'GATEWAY_RESTORE_FAILED' }
            Wait-OwnerHealth 8080
        } catch {
            Write-Output "OWNER_RESTORE_FAILED: $($_.Exception.Message)"
            $failed = $true
        }
    }
}

if ($failed) { exit 1 }
