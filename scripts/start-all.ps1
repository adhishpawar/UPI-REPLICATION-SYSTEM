<#
.SYNOPSIS
    Start all four backend services and the showcase, each in its own window.

.DESCRIPTION
    Convenience only. Each service still runs as its own process with its own
    log; this just saves opening five terminals by hand.

    Startup order does not matter. The services do not require each other to
    boot: vpa and bank are leaves, psp is independent, and the orchestrator
    fetches psp's JWK Set lazily on the first token it has to verify rather
    than at startup. A platform whose services must be started in a particular
    sequence is a platform that cannot survive one of them restarting.

.PARAMETER Wait
    Poll until every service reports healthy before returning.

.EXAMPLE
    .\scripts\start-all.ps1 -Wait
#>
param(
    [switch]$Wait,
    [switch]$NoShowcase
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$services = @(
    @{ Name = "vpa-service";        Port = 8081; Offline = $true  },
    @{ Name = "psp-service";        Port = 8082; Offline = $true  },
    # The orchestrator needs one online Maven run the first time: the local
    # repository is missing spring-boot-maven-plugin:3.3.2.
    @{ Name = "paymentOrchestrator"; Port = 8083; Offline = $false },
    @{ Name = "bank-service";       Port = 8084; Offline = $true  }
)

function Test-Port {
    param([int]$Port)
    $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

foreach ($svc in $services) {
    if (Test-Port -Port $svc.Port) {
        Write-Host "already running on :$($svc.Port)  $($svc.Name)" -ForegroundColor DarkGray
        continue
    }
    $dir = Join-Path $repoRoot $svc.Name
    $offlineFlag = if ($svc.Offline) { "-o " } else { "" }
    $cmd = "cd '$dir'; .\mvnw $offlineFlag-DskipTests spring-boot:run"

    Write-Host "starting :$($svc.Port)  $($svc.Name)" -ForegroundColor Cyan
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $cmd | Out-Null
}

if (-not $NoShowcase) {
    if (Test-Port -Port 8090) {
        Write-Host "already running on :8090  showcase" -ForegroundColor DarkGray
    } else {
        $showcaseDir = Join-Path $repoRoot "backend-showcase"
        Write-Host "starting :8090  backend-showcase" -ForegroundColor Cyan
        Start-Process powershell -ArgumentList "-NoExit", "-Command", `
            "jwebserver -p 8090 -d '$showcaseDir' -b 127.0.0.1" | Out-Null
    }
}

if ($Wait) {
    Write-Host ""
    Write-Host "waiting for health checks..." -ForegroundColor Yellow
    $deadline = (Get-Date).AddMinutes(3)
    foreach ($svc in $services) {
        $healthy = $false
        while (-not $healthy -and (Get-Date) -lt $deadline) {
            try {
                Invoke-RestMethod -Uri "http://localhost:$($svc.Port)/actuator/health" `
                    -TimeoutSec 2 | Out-Null
                $healthy = $true
            } catch { Start-Sleep -Seconds 3 }
        }
        if ($healthy) { Write-Host "  UP    :$($svc.Port)  $($svc.Name)" -ForegroundColor Green }
        else          { Write-Host "  DOWN  :$($svc.Port)  $($svc.Name) - check its window" -ForegroundColor Red }
    }
}

Write-Host ""
Write-Host "Next:" -ForegroundColor Green
Write-Host "  .\scripts\seed-demo-data.ps1     # create a payer, payee and balance"
Write-Host "  .\scripts\verify.ps1             # prove the money invariants hold"
Write-Host "  http://localhost:8090            # the showcase"
