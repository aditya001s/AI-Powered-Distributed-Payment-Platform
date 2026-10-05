[CmdletBinding()]
param(
    [ValidateSet('config', 'build', 'start', 'wait', 'status', 'stats', 'stop', 'help')]
    [string]$Action = 'help',
    [ValidateRange(60, 3600)]
    [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = 'Stop'
$script:RepositoryRoot = Split-Path -Parent $PSScriptRoot
$script:ComposeFiles = @('-f', 'docker-compose.yml', '-f', 'docker-compose.local.yml')

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Compose([string[]]$Arguments) {
    Invoke-Docker -Arguments (@('compose') + $script:ComposeFiles + $Arguments)
}

function Assert-Docker {
    Invoke-Docker -Arguments @('info', '--format', 'Docker {{.ServerVersion}}: {{.MemTotal}} bytes available, {{.ContainersRunning}} container(s) running')
}

function Wait-ServiceGroup([string[]]$Services, [int]$Timeout) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    do {
        $pending = @()
        foreach ($service in $Services) {
            $ids = @(& docker compose @script:ComposeFiles ps -q $service 2>$null)
            if ($LASTEXITCODE -ne 0 -or $ids.Count -eq 0) {
                $pending += "$service (missing)"
                continue
            }
            $state = (& docker inspect $ids[0] --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}').Trim()
            if ($LASTEXITCODE -ne 0 -or ($state -ne 'running|healthy' -and $state -ne 'running|none')) {
                $pending += "$service ($state)"
            }
        }
        if ($pending.Count -eq 0) {
            Write-Host "[PASS] Healthy: $($Services -join ', ')" -ForegroundColor Green
            return
        }
        Write-Host "Waiting: $($pending -join ', ')"
        Start-Sleep -Seconds 10
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    Invoke-Compose -Arguments @('ps', '-a')
    throw "Timed out waiting for: $($Services -join ', ')"
}

function Test-Configuration {
    Write-Step 'Validating merged low-memory Compose configuration'
    Invoke-Compose -Arguments @('config', '--quiet')
}

function Build-Platform {
    Test-Configuration
    $previousParallelLimit = $env:COMPOSE_PARALLEL_LIMIT
    $env:COMPOSE_PARALLEL_LIMIT = '1'
    try {
        Write-Step 'Pulling infrastructure images first (PostgreSQL, Redis, Kafka)'
        Invoke-Compose -Arguments @('pull', 'postgres', 'redis', 'kafka')

        Write-Step 'Building Java group 1/4 serially: gateway, auth, wallet'
        Invoke-Compose -Arguments @('build', 'api-gateway', 'auth-service', 'wallet-service')

        Write-Step 'Building Java group 2/4 serially: ledger, payment, orchestrator'
        Invoke-Compose -Arguments @('build', 'ledger-service', 'payment-service', 'payment-orchestrator')

        Write-Step 'Building Java group 3/4 serially: fraud, notification'
        Invoke-Compose -Arguments @('build', 'fraud-service', 'notification-service')

        Write-Step 'Building Java group 4/4 serially: AI, analytics'
        Invoke-Compose -Arguments @('build', 'ai-investigation-service', 'analytics-service')

        Write-Step 'Building ML image (training layer is cached by training inputs)'
        Invoke-Compose -Arguments @('build', 'ml-service')

        Write-Step 'Building frontend image'
        Invoke-Compose -Arguments @('build', 'frontend')
    }
    finally {
        $env:COMPOSE_PARALLEL_LIMIT = $previousParallelLimit
    }
}

function Start-ServiceWave([string]$Description, [string[]]$Services) {
    Write-Step $Description
    Invoke-Compose -Arguments (@('up', '-d', '--no-build', '--no-deps') + $Services)
    Wait-ServiceGroup -Services $Services -Timeout $TimeoutSeconds
}

function Invoke-PanelReadiness {
    $previousMode = $env:AEGIS_LOW_MEMORY_MODE
    try {
        $env:AEGIS_LOW_MEMORY_MODE = 'true'
        & (Join-Path $PSScriptRoot 'panel-demo.ps1') wait -TimeoutSeconds $TimeoutSeconds
        if ($LASTEXITCODE -ne 0) {
            throw 'panel-demo readiness checks failed'
        }
    }
    finally {
        $env:AEGIS_LOW_MEMORY_MODE = $previousMode
    }
}

function Start-Platform {
    Test-Configuration
    Start-ServiceWave 'Starting infrastructure: PostgreSQL, Redis, Kafka' @('postgres', 'redis', 'kafka')
    Start-ServiceWave 'Starting identity service' @('auth-service')
    Start-ServiceWave 'Starting core and event backends' @(
        'wallet-service', 'ledger-service', 'payment-service',
        'notification-service', 'analytics-service', 'payment-orchestrator'
    )
    Start-ServiceWave 'Starting pre-trained ML runtime' @('ml-service')
    Start-ServiceWave 'Starting fraud and AI/RAG services' @('fraud-service', 'ai-investigation-service')
    Start-ServiceWave 'Starting API gateway' @('api-gateway')
    Start-ServiceWave 'Starting frontend' @('frontend')

    Write-Step 'Running the existing 15-container and 13-endpoint readiness checks'
    Invoke-PanelReadiness
}

function Show-Stats {
    Write-Step 'Configured container memory limits and restart state'
    $ids = @(& docker compose @script:ComposeFiles ps -q)
    foreach ($id in $ids) {
        & docker inspect $id --format '{{.Name}}|limit={{.HostConfig.Memory}}|reservation={{.HostConfig.MemoryReservation}}|restart={{.RestartCount}}|oom={{.State.OOMKilled}}|health={{if .State.Health}}{{.State.Health.Status}}{{end}}'
    }
    if ($LASTEXITCODE -ne 0) { throw 'docker inspect failed' }

    Write-Step 'Current container memory usage'
    Invoke-Docker -Arguments @('stats', '--no-stream', '--format', '{{.Name}}|{{.MemUsage}}|{{.MemPerc}}|{{.CPUPerc}}|{{.PIDs}}')
}

$originalLocation = Get-Location
try {
    Set-Location $script:RepositoryRoot
    switch ($Action) {
        'config' { Assert-Docker; Test-Configuration }
        'build' { Assert-Docker; Build-Platform }
        'start' { Assert-Docker; Start-Platform; Show-Stats }
        'wait' {
            Assert-Docker
            Invoke-PanelReadiness
        }
        'status' { Assert-Docker; Invoke-Compose -Arguments @('ps', '-a') }
        'stats' { Assert-Docker; Show-Stats }
        'stop' {
            Assert-Docker
            Invoke-Compose -Arguments @('down')
            Write-Host '[PASS] Containers stopped; named volumes and built images were retained.' -ForegroundColor Green
        }
        'help' {
            Write-Host @'
AegisPay low-memory Docker workflow (8-GB Windows PC)

Usage:
  .\scripts\low-memory.ps1 config
  .\scripts\low-memory.ps1 build
  .\scripts\low-memory.ps1 start -TimeoutSeconds 1200
  .\scripts\low-memory.ps1 stats
  .\scripts\low-memory.ps1 stop

Actions:
  config  Validate docker-compose.yml + docker-compose.local.yml.
  build   Pull infrastructure, serialize Java image groups, then build ML/frontend.
  start   Start all 15 services in dependency waves without rebuilding.
  wait    Run the existing complete container and endpoint readiness checks.
  status  Show all constrained Compose containers.
  stats   Show enforced limits, restart/OOM state, and current memory usage.
  stop    Stop containers while retaining PostgreSQL, Redis, Kafka, and images.

See docs/LOW_MEMORY_DOCKER.md for limits, Docker Desktop/WSL settings, and tuning rules.
'@
        }
    }
}
finally {
    Set-Location $originalLocation
}
