# ==============================================================================
# Script: runpostgres.ps1
# Description: Loads postgres.local.env and launches the backend connected
#              to Neon PostgreSQL.
# ==============================================================================

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# 1. Stop any existing process running on port 8080
$existingConn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
if ($existingConn) {
    Write-Host "[*] Freeing port 8080..." -ForegroundColor Yellow
    Stop-Process -Id $existingConn.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

# 2. Locate and load postgres.local.env
$envFile = Join-Path $scriptDir "postgres.local.env"
if (-not (Test-Path $envFile)) {
    $parentEnv = Join-Path (Split-Path -Parent $scriptDir) "postgres.local.env"
    if (Test-Path $parentEnv) {
        $envFile = $parentEnv
    }
}

if (Test-Path $envFile) {
    Write-Host "[+] Loading environment variables from $envFile" -ForegroundColor Cyan
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not ($line.StartsWith("#"))) {
            $key, $value = $line.Split("=", 2)
            if ($key -and $value) {
                [System.Environment]::SetEnvironmentVariable($key.Trim(), $value.Trim(), "Process")
            }
        }
    }
} else {
    Write-Host "[!] Warning: postgres.local.env not found. Using defaults in application-postgres.yml" -ForegroundColor Yellow
}

# 3. Ensure target JAR exists; compile if missing
$jarPath = Join-Path $scriptDir "target\mrlbs-backend-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jarPath)) {
    Write-Host "[*] Target JAR not found. Building with Maven..." -ForegroundColor Cyan
    & ".\mvnw.cmd" package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[-] Build failed." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# 4. Launch Spring Boot with postgres and dev profiles
Write-Host "[+] Connecting to PostgreSQL (Neon cloud) and starting backend on port 8080..." -ForegroundColor Green
Write-Host "[*] Active profile: postgres" -ForegroundColor DarkGray
Write-Host "[*] Press Ctrl+C to stop the server.`n" -ForegroundColor DarkGray

java -jar $jarPath --spring.profiles.active=postgres $args
