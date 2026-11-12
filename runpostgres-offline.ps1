# ==============================================================================
# Script: runpostgres-offline.ps1
# Description: Launches the backend connected to Neon PostgreSQL with
#              GEMINI_API_KEY suppressed for testing AI Fallback / Offline mode.
# ==============================================================================

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# 1. Stop any existing process running on port 8080
try {
    $existingConn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($existingConn) {
        Write-Host "[*] Freeing port 8080..." -ForegroundColor Yellow
        $pids = $existingConn | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($pidToStop in $pids) {
            if ($pidToStop -and $pidToStop -ne 0) {
                Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue
            }
        }
        Start-Sleep -Seconds 1
    }
} catch {
    # Proceed even if net connection query cannot be performed
}

# 2. Locate and load postgres.local.env (EXCLUDING GEMINI_API_KEY)
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
                $trimmedKey = $key.Trim()
                if ($trimmedKey -ne "GEMINI_API_KEY") {
                    [System.Environment]::SetEnvironmentVariable($trimmedKey, $value.Trim(), "Process")
                }
            }
        }
    }
}

# Explicitly ensure GEMINI_API_KEY is empty in process
[System.Environment]::SetEnvironmentVariable("GEMINI_API_KEY", "", "Process")
$env:GEMINI_API_KEY = ""

Write-Host "==========================================================" -ForegroundColor Yellow
Write-Host "[*] AI OFFLINE MODE: GEMINI_API_KEY is completely disabled" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Yellow

# 3. Ensure target JAR exists and is up-to-date; compile if missing or modified
$jarPath = Join-Path $scriptDir "target\mrlbs-backend-0.0.1-SNAPSHOT.jar"
$needsBuild = -not (Test-Path $jarPath)

if (-not $needsBuild) {
    $jarDate = (Get-Item $jarPath).LastWriteTime
    $newerSrc = Get-ChildItem -Path (Join-Path $scriptDir "src") -Recurse -File | Where-Object { $_.LastWriteTime -gt $jarDate } | Select-Object -First 1
    if ($newerSrc) {
        Write-Host "[*] Source modifications detected. Rebuilding target JAR with Maven..." -ForegroundColor Cyan
        $needsBuild = $true
    }
}

if ($needsBuild) {
    Write-Host "[*] Building target JAR with Maven..." -ForegroundColor Cyan
    & ".\mvnw.cmd" package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[-] Build failed." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# 4. Launch Spring Boot with postgres profile and gemini disabled via JVM argument
Write-Host "[+] Connecting to PostgreSQL (Neon cloud) in AI OFFLINE mode on port 8080..." -ForegroundColor Green
Write-Host "[*] Active profile: postgres (Gemini Offline)" -ForegroundColor DarkGray
Write-Host "[*] Press Ctrl+C to stop the server.`n" -ForegroundColor DarkGray

java -Dmrlbs.ai.gemini.api-key="" -Dgemini.api-key="" -jar $jarPath --spring.profiles.active=postgres --mrlbs.ai.gemini.api-key="" $args
