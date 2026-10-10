# run-postgres.ps1
# ------------------------------------------------------------
# Convenience script to start the MRLBS Spring Boot backend using
# the Neon PostgreSQL database.
# ------------------------------------------------------------
# Expected location of a local, untracked config file (not committed):
#   postgres.local.env
# It should contain two lines (no quoting needed):
#   SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>?sslmode=require
#   SPRING_DATASOURCE_USERNAME=<your-neon-username>
# The password will be prompted securely at runtime.

function Throw-Error([string]$msg) {
    Write-Host "ERROR: $msg" -ForegroundColor Red
    exit 1
}

$scriptDir = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$envFile = Join-Path $scriptDir "postgres.local.env"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*SPRING_DATASOURCE_URL\s*=\s*([^\s#]+)') { $datasourceUrl = $Matches[1].Trim().Trim('"').Trim("'") }
        elseif ($line -match '^\s*SPRING_DATASOURCE_USERNAME\s*=\s*([^\s#]+)') { $datasourceUser = $Matches[1].Trim().Trim('"').Trim("'") }
        elseif ($line -match '^\s*SPRING_DATASOURCE_PASSWORD\s*=\s*([^\s#]+)') { $datasourcePassword = $Matches[1].Trim().Trim('"').Trim("'") }
    }
}

if (-not $datasourceUrl) { Throw-Error "Missing SPRING_DATASOURCE_URL. Define it in postgres.local.env or edit the script." }
if (-not $datasourceUser) { Throw-Error "Missing SPRING_DATASOURCE_USERNAME. Define it in postgres.local.env or edit the script." }
# Password is optional – will be prompted only if not defined.


# Attempt to read password from the env file (plain‑text). If not present, fall back to a secure prompt.
if (-not $datasourcePassword) {
    # No password in the env file – ask the user securely.
    $securePwd = Read-Host -AsSecureString "Enter Neon PostgreSQL password"
    $pwdBSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePwd)
    $plainPwd = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($pwdBSTR)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pwdBSTR) | Out-Null
} else {
    # Password was supplied in the env file – use it directly (still a plain string in memory only).
    $plainPwd = $datasourcePassword
}

$env:SPRING_PROFILES_ACTIVE = "postgres"
$env:SPRING_DATASOURCE_URL = $datasourceUrl
$env:SPRING_DATASOURCE_USERNAME = $datasourceUser
$env:SPRING_DATASOURCE_PASSWORD = $plainPwd   # may come from file or prompt

Push-Location $scriptDir
try {
    # If port 8080 is already occupied by an old instance, clear it first
    $existing = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($existing) {
        $pidsToStop = $existing | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($procId in $pidsToStop) {
            Write-Host "Port 8080 was in use by PID $procId. Stopping previous process..." -ForegroundColor Yellow
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "Starting MRLBS backend with Neon PostgreSQL..." -ForegroundColor Cyan
    .\mvnw.cmd -e spring-boot:run
}
finally {
    Pop-Location
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
}
