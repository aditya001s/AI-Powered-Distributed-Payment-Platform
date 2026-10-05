[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help', 'setup', 'start', 'wait', 'seed', 'verify', 'prepare', 'status', 'credentials', 'stop', 'reset')]
    [string]$Action = 'help',

    [ValidateRange(30, 3600)]
    [int]$TimeoutSeconds = 1200,

    [switch]$NoBuild,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepositoryRoot = Split-Path -Parent $PSScriptRoot
$script:EnvironmentFile = Join-Path $script:RepositoryRoot '.env'
$script:EnvironmentTemplate = Join-Path $script:RepositoryRoot '.env.example'
$script:ScenarioFile = Join-Path $script:RepositoryRoot '.panel-demo.json'
$script:VerificationFile = Join-Path $script:RepositoryRoot '.panel-verification.json'
$script:BaseUrl = 'http://127.0.0.1:8080'

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Pass([string]$Message) {
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return ([Convert]::ToBase64String($bytes)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-DemoPassword {
    return 'Panel-' + (New-LocalSecret).Substring(0, 24) + '!Aa9'
}

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. Install it and retry."
    }
}

function Invoke-Docker([string[]]$Arguments, [switch]$Capture) {
    # Native stderr is represented as PowerShell error records in Windows PowerShell.
    # Temporarily keep it non-terminating so this wrapper can report one concise error.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Capture) {
            $output = & docker @Arguments 2>&1
            $exitCode = $LASTEXITCODE
            if ($exitCode -ne 0) {
                throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
            }
            return $output
        }

        & docker @Arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "docker $($Arguments -join ' ') failed with exit code $exitCode"
        }
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Assert-Docker {
    Assert-Command 'docker'
    Invoke-Docker -Arguments @('info') -Capture | Out-Null
    Invoke-Docker -Arguments @('compose', 'version') -Capture | Out-Null

    $memoryText = (Invoke-Docker -Arguments @('info', '--format', '{{.MemTotal}}') -Capture | Out-String).Trim()
    $memoryBytes = 0L
    if ([long]::TryParse($memoryText, [ref]$memoryBytes)) {
        $memoryGiB = [math]::Round($memoryBytes / 1GB, 1)
        if ($env:AEGIS_LOW_MEMORY_MODE -eq 'true') {
            if ($memoryBytes -lt 3.5GB) {
                Write-Warn "Docker reports ${memoryGiB} GiB memory. The constrained profile needs at least 3.5 GiB; 5 GiB is recommended on an 8-GB host."
            }
            else {
                Write-Pass "Docker is available with ${memoryGiB} GiB for the constrained local profile"
            }
        }
        elseif ($memoryBytes -lt 8GB) {
            Write-Warn "Docker reports ${memoryGiB} GiB memory. Allocate at least 8 GiB; 10+ GiB is recommended for the standard full stack."
        }
        else {
            Write-Pass "Docker is available with ${memoryGiB} GiB memory"
        }
    }
    else {
        Write-Pass 'Docker Engine and Compose v2 are available'
    }
}

function Read-DotEnv {
    if (-not (Test-Path $script:EnvironmentFile)) {
        throw '.env does not exist. Run: .\scripts\panel-demo.ps1 setup'
    }

    $values = @{}
    foreach ($line in Get-Content $script:EnvironmentFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1)
        $values[$name] = $value
    }
    return $values
}

function Set-DotEnvValue([string]$Content, [string]$Name, [string]$Value) {
    $escapedName = [regex]::Escape($Name)
    if ($Content -match "(?m)^${escapedName}=") {
        return [regex]::Replace($Content, "(?m)^${escapedName}=.*$", "${Name}=${Value}")
    }
    return $Content.TrimEnd() + "`n${Name}=${Value}`n"
}

function Initialize-Environment {
    Write-Step 'Preparing an untracked local panel environment'
    if (-not (Test-Path $script:EnvironmentTemplate)) {
        throw ".env.example was not found at $script:EnvironmentTemplate"
    }

    $created = -not (Test-Path $script:EnvironmentFile)
    if ($created) {
        Copy-Item $script:EnvironmentTemplate $script:EnvironmentFile
    }

    $content = Get-Content -Raw $script:EnvironmentFile
    $replacements = [ordered]@{
        'CHANGE_ME_strong_db_password'       = New-LocalSecret
        'CHANGE_ME_strong_redis_password'    = New-LocalSecret
        'CHANGE_ME_internal_service_secret'  = New-LocalSecret
        'CHANGE_ME_wallet_service_client_secret' = New-LocalSecret
        'CHANGE_ME_ledger_service_client_secret' = New-LocalSecret
        'CHANGE_ME_payment_service_client_secret' = New-LocalSecret
        'CHANGE_ME_payment_orchestrator_client_secret' = New-LocalSecret
        'CHANGE_ME_fraud_service_client_secret' = New-LocalSecret
        'CHANGE_ME_notification_service_client_secret' = New-LocalSecret
        'CHANGE_ME_analytics_service_client_secret' = New-LocalSecret
        'CHANGE_ME_ml_service_api_key'       = New-LocalSecret
        'CHANGE_ME_grafana_password'         = New-LocalSecret
        'CHANGE_ME_auth_database_password'    = New-LocalSecret
        'CHANGE_ME_wallet_database_password'  = New-LocalSecret
        'CHANGE_ME_ledger_database_password'  = New-LocalSecret
        'CHANGE_ME_payment_database_password' = New-LocalSecret
        'CHANGE_ME_orchestrator_database_password' = New-LocalSecret
        'CHANGE_ME_fraud_database_password'   = New-LocalSecret
        'CHANGE_ME_ai_database_password'      = New-LocalSecret
        'CHANGE_ME_notification_database_password' = New-LocalSecret
        'CHANGE_ME_analytics_database_password' = New-LocalSecret
        'CHANGE_ME_DemoUser#2026'            = New-DemoPassword
        'CHANGE_ME_DemoMerchant#2026'        = New-DemoPassword
        'CHANGE_ME_DemoAdmin#2026'           = New-DemoPassword
        'AegisPanelUser-2026!'                = New-DemoPassword
        'AegisPanelMerchant-2026!'            = New-DemoPassword
        'AegisPanelAdmin-2026!'               = New-DemoPassword
    }
    foreach ($entry in $replacements.GetEnumerator()) {
        $content = $content.Replace($entry.Key, [string]$entry.Value)
    }

    foreach ($generatedSecret in @('AUTH_DB_PASSWORD', 'WALLET_DB_PASSWORD', 'LEDGER_DB_PASSWORD',
            'PAYMENT_DB_PASSWORD', 'ORCHESTRATOR_DB_PASSWORD', 'FRAUD_DB_PASSWORD',
            'AI_DB_PASSWORD', 'NOTIFICATION_DB_PASSWORD', 'ANALYTICS_DB_PASSWORD',
            'WALLET_SERVICE_INTERNAL_CLIENT_SECRET', 'LEDGER_SERVICE_INTERNAL_CLIENT_SECRET',
            'PAYMENT_SERVICE_INTERNAL_CLIENT_SECRET', 'PAYMENT_ORCHESTRATOR_INTERNAL_CLIENT_SECRET',
            'FRAUD_SERVICE_INTERNAL_CLIENT_SECRET', 'NOTIFICATION_SERVICE_INTERNAL_CLIENT_SECRET',
            'ANALYTICS_SERVICE_INTERNAL_CLIENT_SECRET')) {
        if ($content -notmatch "(?m)^$([regex]::Escape($generatedSecret))=") {
            $content = Set-DotEnvValue $content $generatedSecret (New-LocalSecret)
        }
    }

    if ($created) {
        # Keep the evaluation path deterministic. Fault injection remains available
        # through the provider simulation APIs, but random failures are disabled.
        foreach ($provider in @('A', 'B', 'C')) {
            $content = Set-DotEnvValue $content "PROVIDER_${provider}_FAILURE_RATE" '0.0'
        }
        $content = Set-DotEnvValue $content 'NOTIFICATION_SIMULATED_FAILURE_RATE' '0.0'
        $content = Set-DotEnvValue $content 'DEMO_SEED_ENABLED' 'true'
    }

    [System.IO.File]::WriteAllText($script:EnvironmentFile, $content, (New-Object System.Text.UTF8Encoding($false)))

    $remaining = Select-String -Path $script:EnvironmentFile -Pattern '^[A-Z][A-Z0-9_]*=.*CHANGE_ME'
    if ($remaining) {
        throw '.env still contains CHANGE_ME values. Replace them before starting the platform.'
    }

    $environment = Read-DotEnv
    foreach ($required in @('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'ML_API_KEY',
            'WALLET_SERVICE_INTERNAL_CLIENT_SECRET', 'LEDGER_SERVICE_INTERNAL_CLIENT_SECRET',
            'PAYMENT_SERVICE_INTERNAL_CLIENT_SECRET', 'PAYMENT_ORCHESTRATOR_INTERNAL_CLIENT_SECRET',
            'FRAUD_SERVICE_INTERNAL_CLIENT_SECRET', 'NOTIFICATION_SERVICE_INTERNAL_CLIENT_SECRET',
            'ANALYTICS_SERVICE_INTERNAL_CLIENT_SECRET',
            'AUTH_DB_PASSWORD', 'WALLET_DB_PASSWORD', 'LEDGER_DB_PASSWORD', 'PAYMENT_DB_PASSWORD',
            'ORCHESTRATOR_DB_PASSWORD', 'FRAUD_DB_PASSWORD', 'AI_DB_PASSWORD',
            'NOTIFICATION_DB_PASSWORD', 'ANALYTICS_DB_PASSWORD',
            'DEMO_USER_EMAIL', 'DEMO_USER_PASSWORD', 'DEMO_MERCHANT_EMAIL', 'DEMO_MERCHANT_PASSWORD',
            'DEMO_ADMIN_EMAIL', 'DEMO_ADMIN_PASSWORD')) {
        if (-not $environment.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($environment[$required])) {
            throw "Required .env value '$required' is missing or blank."
        }
    }

    if ($created) {
        $existingVolume = & docker volume ls --quiet --filter 'name=^aegis-postgres-data$' 2>$null
        if ($LASTEXITCODE -eq 0 -and $existingVolume) {
            Write-Warn 'A previous PostgreSQL volume exists but .env was newly generated. If startup reports authentication errors, run reset -Force once.'
        }
        Write-Pass 'Created .env with generated infrastructure secrets and local-only panel accounts'
    }
    else {
        Write-Pass 'Validated the existing .env and replaced any template placeholders'
    }
}

function Show-Credentials {
    $environment = Read-DotEnv
    Write-Host ''
    Write-Host 'Local panel accounts (never use these outside this laptop):' -ForegroundColor Cyan
    Write-Host "  Customer : $($environment['DEMO_USER_EMAIL']) / $($environment['DEMO_USER_PASSWORD'])"
    Write-Host "  Merchant : $($environment['DEMO_MERCHANT_EMAIL']) / $($environment['DEMO_MERCHANT_PASSWORD'])"
    Write-Host "  Operator : $($environment['DEMO_ADMIN_EMAIL']) / $($environment['DEMO_ADMIN_PASSWORD'])"
    Write-Host '  Dashboard: http://127.0.0.1:5173'
    Write-Host '  Swagger  : http://127.0.0.1:8080/swagger-ui.html'
}

function Convert-ComposeRows([string]$Raw) {
    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return @()
    }
    try {
        return @($Raw | ConvertFrom-Json)
    }
    catch {
        $rows = @()
        foreach ($line in ($Raw -split "`r?`n")) {
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                $rows += $line | ConvertFrom-Json
            }
        }
        return $rows
    }
}

function Get-ComposeRows {
    $raw = (Invoke-Docker -Arguments @('compose', 'ps', '--all', '--format', 'json') -Capture | Out-String)
    return @(Convert-ComposeRows $raw)
}

function Wait-Compose([int]$Timeout) {
    Write-Step 'Waiting for every Compose service to be running and healthy'
    $expected = @(Invoke-Docker -Arguments @('compose', 'config', '--services') -Capture)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    $lastPending = @()

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $rows = @(Get-ComposeRows)
        $pending = @()
        $failed = @()

        foreach ($service in $expected) {
            $row = $rows | Where-Object { $_.Service -eq $service } | Select-Object -First 1
            if ($null -eq $row) {
                $pending += "$service (not created)"
                continue
            }
            $state = [string]$row.State
            $health = if ($row.PSObject.Properties.Name -contains 'Health') { [string]$row.Health } else { '' }
            if ($state -in @('exited', 'dead', 'removing')) {
                $failed += "$service ($state)"
            }
            elseif ($state -ne 'running' -or ($health -and $health -ne 'healthy')) {
                $label = if ($health) { "$state/$health" } else { $state }
                $pending += "$service ($label)"
            }
        }

        if ($failed.Count -gt 0) {
            Invoke-Docker -Arguments @('compose', 'ps')
            throw "Compose service failure: $($failed -join ', ')"
        }
        if ($pending.Count -eq 0) {
            Write-Pass "All $($expected.Count) Compose services are running and healthy"
            return
        }
        if (($pending -join ', ') -ne ($lastPending -join ', ')) {
            Write-Host "Waiting: $($pending -join ', ')"
            $lastPending = $pending
        }
        Start-Sleep -Seconds 5
    }

    Invoke-Docker -Arguments @('compose', 'ps')
    Invoke-Docker -Arguments @('compose', 'logs', '--tail', '80')
    throw "Timed out after $Timeout seconds waiting for Compose health."
}

function Test-Endpoint([string]$Uri) {
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 5
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    }
    catch {
        return $false
    }
}

function Wait-Endpoints([int]$Timeout) {
    Write-Step 'Checking every externally testable service endpoint'
    $endpoints = [ordered]@{
        'gateway'       = 'http://127.0.0.1:8080/actuator/health/readiness'
        'auth'          = 'http://127.0.0.1:8081/actuator/health/readiness'
        'wallet'        = 'http://127.0.0.1:8082/actuator/health/readiness'
        'ledger'        = 'http://127.0.0.1:8083/actuator/health/readiness'
        'payment'       = 'http://127.0.0.1:8084/actuator/health/readiness'
        'orchestrator'  = 'http://127.0.0.1:8085/actuator/health/readiness'
        'fraud'         = 'http://127.0.0.1:8086/actuator/health/readiness'
        'investigation' = 'http://127.0.0.1:8087/actuator/health/readiness'
        'notification'  = 'http://127.0.0.1:8088/actuator/health/readiness'
        'analytics'     = 'http://127.0.0.1:8089/actuator/health/readiness'
        'ml'            = 'http://127.0.0.1:8000/health/readiness'
        'frontend'      = 'http://127.0.0.1:5173/healthz'
        'jwks'          = 'http://127.0.0.1:8081/.well-known/jwks.json'
    }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    $pending = @($endpoints.Keys)

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $pending = @($endpoints.GetEnumerator() | Where-Object { -not (Test-Endpoint $_.Value) } | ForEach-Object { $_.Key })
        if ($pending.Count -eq 0) {
            Write-Pass "All $($endpoints.Count) service endpoints responded successfully"
            return
        }
        Write-Host "Endpoint wait: $($pending -join ', ')"
        Start-Sleep -Seconds 5
    }
    throw "Timed out waiting for endpoints: $($pending -join ', ')"
}

function Invoke-AegisApi {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST', 'PATCH')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Token,
        [object]$Body,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 0
    )

    $requestHeaders = @{}
    foreach ($entry in $Headers.GetEnumerator()) {
        $requestHeaders[$entry.Key] = $entry.Value
    }
    if ($Token) {
        $requestHeaders['Authorization'] = "Bearer $Token"
    }

    # A constrained local JVM pays its whole cold-start cost on the first call through
    # each path: JIT compilation, the first query plan, and cold gateway filters such as
    # the JWKS fetch and the rate limiter's Redis script. This only decides how patient
    # the test client is; gateway and business timeouts are deliberately untouched.
    if ($TimeoutSec -le 0) {
        $TimeoutSec = if ($env:AEGIS_LOW_MEMORY_MODE -eq 'true') { 90 } else { 20 }
    }

    $parameters = @{
        Uri         = "$script:BaseUrl$Path"
        Method      = $Method
        Headers     = $requestHeaders
        TimeoutSec  = $TimeoutSec
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = $Body | ConvertTo-Json -Depth 12 -Compress
    }

    try {
        return Invoke-RestMethod @parameters
    }
    catch {
        throw "$Method $Path failed: $($_.Exception.Message)"
    }
}

function Login([string]$Email, [string]$Password) {
    $response = Invoke-AegisApi -Method POST -Path '/api/auth/login' -Body @{ email = $Email; password = $Password }
    if (-not $response.accessToken -or -not $response.user.userId) {
        throw "Login for $Email returned no access token or user id."
    }
    return $response
}

function Ensure-Wallet([string]$Token) {
    # Windows PowerShell 5 can preserve an Invoke-RestMethod JSON array as one
    # scalar object across a function boundary. Force pipeline enumeration so an
    # empty response stays empty and each wallet is filtered individually.
    $response = Invoke-AegisApi -Method GET -Path '/api/wallets' -Token $Token
    $wallets = if ($null -eq $response) { @() } else { @($response | ForEach-Object { $_ }) }
    $wallet = $wallets | Where-Object { $_.currency -eq 'INR' } | Select-Object -First 1
    if ($null -eq $wallet) {
        $wallet = Invoke-AegisApi -Method POST -Path '/api/wallets' -Token $Token -Body @{ currency = 'INR' }
    }
    return $wallet
}

function Ensure-Funds([string]$Token, [object]$Wallet, [string]$RunId) {
    $current = Invoke-AegisApi -Method GET -Path "/api/wallets/$($Wallet.walletId)" -Token $Token
    $targetMinor = 100000L
    if ([long]$current.available.amountMinor -ge $targetMinor) {
        return $current
    }

    $missingMinor = $targetMinor - [long]$current.available.amountMinor
    $amount = [decimal]$missingMinor / 100
    $headers = @{ 'Idempotency-Key' = "panel-fund-$RunId" }
    $result = Invoke-AegisApi -Method POST -Path "/api/wallets/$($Wallet.walletId)/deposit" -Token $Token -Headers $headers -Body @{
        amount = $amount
        currency = 'INR'
        description = 'Panel demonstration funding (simulated)'
    }
    return $result.wallet
}

function Submit-Payment {
    param(
        [string]$Token,
        [string]$DestinationWalletId,
        [decimal]$Amount,
        [string]$Description,
        [string]$IdempotencyKey,
        [hashtable]$RiskContext,
        [int]$Attempts = 3
    )
    $body = @{
        destinationWalletId = $DestinationWalletId
        amount = $Amount
        currency = 'INR'
        paymentType = 'P2P_TRANSFER'
        description = $Description
        riskContext = $RiskContext
    }

    # The first payment on a cold stack crosses gateway, payment, orchestrator, fraud and
    # ML in one request and can outlast the gateway's proxy timeout. Retrying with the
    # SAME Idempotency-Key is safe by the endpoint's own guarantee: the retry either
    # accepts the payment once or returns the already-accepted one. Only transient
    # timeout/5xx responses are retried, so a real rejection still fails immediately.
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            return Invoke-AegisApi -Method POST -Path '/api/payments' -Token $Token -Headers @{
                'Idempotency-Key' = $IdempotencyKey
            } -Body $body
        }
        catch {
            $transient = $_.Exception.Message -match 'timed out|timeout|\(50[234]\)'
            if ($attempt -eq $Attempts -or -not $transient) {
                throw
            }
            Write-Warn "Payment submission attempt $attempt did not return in time while services warm; retrying with the same Idempotency-Key."
            Start-Sleep -Seconds 5
        }
    }
}

function Wait-Payment {
    param(
        [string]$Token,
        [string]$PaymentId,
        [string[]]$AcceptedStatuses,
        [int]$Timeout = 30
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    $last = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $last = Invoke-AegisApi -Method GET -Path "/api/payments/$PaymentId" -Token $Token
        if ($last.status -in $AcceptedStatuses) {
            return $last
        }
        if ($last.status -in @('REJECTED', 'FAILED') -and $last.status -notin $AcceptedStatuses) {
            throw "Payment $PaymentId reached unexpected status $($last.status): $($last.failureCode) $($last.failureReason)"
        }
        Start-Sleep -Milliseconds 750
    }
    $status = if ($null -eq $last) { 'unknown' } else { $last.status }
    throw "Payment $PaymentId did not reach $($AcceptedStatuses -join '/') within $Timeout seconds (last status: $status)."
}

function Wait-PanelPaymentOutcome {
    param(
        [string]$UserToken,
        [string]$AdminToken,
        [string]$PaymentId,
        [int]$Timeout = 30
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    $lastPayment = $null
    $lastSaga = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $lastPayment = Invoke-AegisApi -Method GET -Path "/api/payments/$PaymentId" -Token $UserToken
        if ($lastPayment.status -eq 'COMPLETED') {
            return [pscustomobject]@{ held = $false; payment = $lastPayment; saga = $null }
        }
        if ($lastPayment.status -in @('REJECTED', 'FAILED')) {
            throw "Payment $PaymentId reached unexpected status $($lastPayment.status): $($lastPayment.failureCode) $($lastPayment.failureReason)"
        }

        # A fraud hold is deliberately not projected into payment-service: there is
        # no terminal outcome yet. The orchestrator owns that parked state, so poll
        # its read API instead of waiting for a payment event that does not exist.
        try {
            $lastSaga = Invoke-AegisApi -Method GET `
                -Path "/api/orchestration/payments/$PaymentId" -Token $AdminToken
            if ($lastSaga.status -eq 'RISK_HELD') {
                return [pscustomobject]@{ held = $true; payment = $lastPayment; saga = $lastSaga }
            }
        }
        catch {
            # The saga row can legitimately lag payment acceptance by one Kafka
            # delivery. Ignore only that initial 404; every other error is real.
            if ($_.Exception.Message -notmatch '\(404\)') {
                throw
            }
        }
        Start-Sleep -Milliseconds 750
    }
    $paymentStatus = if ($null -eq $lastPayment) { 'unknown' } else { $lastPayment.status }
    $sagaStatus = if ($null -eq $lastSaga) { 'not-created' } else { $lastSaga.status }
    throw "Payment $PaymentId did not complete or reach RISK_HELD within $Timeout seconds (payment=$paymentStatus, saga=$sagaStatus)."
}

function Find-FraudAlert([string]$AdminToken, [string]$PaymentId, [int]$Timeout = 30) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $page = Invoke-AegisApi -Method GET -Path '/api/fraud/alerts?blockingOnly=true&size=100' -Token $AdminToken
        $alert = @($page.content) | Where-Object { $_.paymentId -eq $PaymentId } | Select-Object -First 1
        if ($null -ne $alert) {
            return $alert
        }
        Start-Sleep -Seconds 1
    }
    throw "No blocking fraud alert appeared for payment $PaymentId."
}

function Release-IfHeld([string]$UserToken, [string]$AdminToken, [object]$Outcome,
                        [int]$Timeout = 30) {
    if (-not $Outcome.held) {
        return $Outcome.payment
    }
    $payment = $Outcome.payment
    $alert = Find-FraudAlert $AdminToken $payment.paymentId
    try {
        Invoke-AegisApi -Method POST -Path "/api/fraud/alerts/$($alert.alertId)/take" -Token $AdminToken -Body @{} | Out-Null
    }
    catch {
        Write-Warn "Could not claim low-risk alert $($alert.alertId); attempting the decision in case it is already assigned."
    }
    Invoke-AegisApi -Method POST -Path "/api/fraud/alerts/$($alert.alertId)/decide" -Token $AdminToken -Body @{
        action = 'RELEASE'
        reason = 'Automated panel preparation: allow baseline transaction for deterministic demo history.'
    } | Out-Null
    return Wait-Payment -Token $UserToken -PaymentId $payment.paymentId -AcceptedStatuses @('COMPLETED') -Timeout $Timeout
}

function Wait-Analytics([string]$AdminToken, [string[]]$PaymentIds, [int]$Timeout = 45) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $page = Invoke-AegisApi -Method GET -Path '/api/admin/analytics/payments?size=100' -Token $AdminToken
        $visible = @($page.content | ForEach-Object { $_.paymentId })
        $missing = @($PaymentIds | Where-Object { $_ -notin $visible })
        if ($missing.Count -eq 0) {
            return $page
        }
        Start-Sleep -Seconds 1
    }
    throw 'Analytics did not project every panel payment within the expected time.'
}

function Wait-LiabilityReconciliation([string]$AdminToken, [int]$Timeout = 45) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    $last = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $last = Invoke-AegisApi -Method GET `
            -Path '/api/wallets/admin/reconcile-ledger?currency=INR' -Token $AdminToken
        if ($last.status -eq 'MATCH' -and [long]$last.differenceMinor -eq 0L) {
            return $last
        }
        Start-Sleep -Seconds 1
    }
    $difference = if ($null -eq $last) { 'unknown' } else { $last.differenceMinor }
    throw "Wallet/ledger liabilities did not converge within $Timeout seconds (difference=$difference)."
}

function New-PanelScenario {
    Write-Step 'Creating deterministic wallets, payments, analytics data, and a blocking fraud alert'
    $environment = Read-DotEnv
    $user = Login $environment['DEMO_USER_EMAIL'] $environment['DEMO_USER_PASSWORD']
    $merchant = Login $environment['DEMO_MERCHANT_EMAIL'] $environment['DEMO_MERCHANT_PASSWORD']
    $admin = Login $environment['DEMO_ADMIN_EMAIL'] $environment['DEMO_ADMIN_PASSWORD']
    Write-Pass 'Authenticated customer, merchant, and operator accounts'

    $userWallet = Ensure-Wallet $user.accessToken
    $merchantWallet = Ensure-Wallet $merchant.accessToken
    $runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss')
    # Constrained JVMs intentionally trade startup/throughput for host stability.
    # Measured payment completion can be just over 30 seconds on a 3.8-GiB VM.
    $operationTimeout = if ($env:AEGIS_LOW_MEMORY_MODE -eq 'true') { 90 } else { 30 }
    $userWallet = Ensure-Funds $user.accessToken $userWallet $runId
    Write-Pass "Wallets ready; customer has $($userWallet.available.amountMinor) INR minor units available"

    $mumbai = @{
        deviceId = 'panel-device-a'
        deviceFingerprint = 'panel-fingerprint-a'
        countryCode = 'IN'
        city = 'Mumbai'
        latitude = 19.0760
        longitude = 72.8777
        channel = 'MOBILE_ANDROID'
    }
    $completed = @()
    $amounts = @([decimal]1.00, [decimal]2.00, [decimal]5.00)
    for ($index = 0; $index -lt $amounts.Count; $index++) {
        $accepted = Submit-Payment -Token $user.accessToken -DestinationWalletId $merchantWallet.walletId `
            -Amount $amounts[$index] -Description "Panel baseline payment $($index + 1)" `
            -IdempotencyKey "panel-$runId-baseline-$($index + 1)" -RiskContext $mumbai
        $outcome = Wait-PanelPaymentOutcome -UserToken $user.accessToken `
            -AdminToken $admin.accessToken -PaymentId $accepted.paymentId -Timeout $operationTimeout
        $result = Release-IfHeld $user.accessToken $admin.accessToken $outcome -Timeout $operationTimeout
        $completed += $result
        Write-Pass "Baseline payment $($result.paymentId) completed via $($result.providerId)"
    }

    # Fraud observations arrive through an independent Kafka consumer. Attempt the
    # far-away payment, verify its actual outcome, and alternate locations if a cold
    # consumer had not yet stored the previous anchor. The loop is outcome-based;
    # it never claims a deterministic hold merely because a fixed sleep elapsed.
    $newYork = @{
        deviceId = 'panel-device-a'
        deviceFingerprint = 'panel-fingerprint-a'
        countryCode = 'US'
        city = 'New York'
        latitude = 40.7128
        longitude = -74.0060
        channel = 'MOBILE_ANDROID'
    }
    $held = $null
    for ($attempt = 1; $attempt -le 3 -and $null -eq $held; $attempt++) {
        Start-Sleep -Seconds (2 * $attempt)
        $context = if ($attempt % 2 -eq 1) { $newYork } else { $mumbai }
        $accepted = Submit-Payment -Token $user.accessToken -DestinationWalletId $merchantWallet.walletId `
            -Amount ([decimal]1.00) -Description "Panel impossible-travel fraud hold attempt $attempt" `
            -IdempotencyKey "panel-$runId-impossible-travel-$attempt" -RiskContext $context
        $outcome = Wait-PanelPaymentOutcome -UserToken $user.accessToken `
            -AdminToken $admin.accessToken -PaymentId $accepted.paymentId -Timeout $operationTimeout
        if ($outcome.held) {
            $held = $accepted
            $heldSaga = $outcome.saga
        }
        else {
            Write-Warn "Fraud history was not anchored for attempt $attempt; the completed payment is now the next geographic anchor."
        }
    }
    if ($null -eq $held) {
        throw 'Could not create an impossible-travel HOLD after three verified alternating-location attempts.'
    }
    $alert = Find-FraudAlert $admin.accessToken $held.paymentId -Timeout $operationTimeout
    if (-not $alert.blocksPayment -or $alert.decision -ne 'HOLD') {
        throw "Fraud alert $($alert.alertId) is not the expected blocking HOLD."
    }
    Write-Pass "Impossible-travel payment $($held.paymentId) is RISK_HELD with open alert $($alert.alertId)"

    $paymentIds = @($completed | ForEach-Object { $_.paymentId }) + @($held.paymentId)
    $analytics = Wait-Analytics $admin.accessToken $paymentIds
    $saga = Invoke-AegisApi -Method GET -Path "/api/orchestration/payments/$($held.paymentId)" -Token $admin.accessToken
    if ($saga.status -ne 'RISK_HELD') {
        throw "Expected held saga status RISK_HELD, received $($saga.status)."
    }
    $trialBalance = Invoke-AegisApi -Method GET -Path '/api/ledger/admin/trial-balance?currency=INR' -Token $admin.accessToken
    $reconciliation = Invoke-AegisApi -Method GET -Path '/api/ledger/admin/reconcile' -Token $admin.accessToken
    $liabilityReconciliation = Wait-LiabilityReconciliation $admin.accessToken
    if (-not $trialBalance.balanced -or -not $trialBalance.accountsAgreeWithJournal -or -not $reconciliation.consistent) {
        throw 'Ledger integrity verification failed after preparing panel transactions.'
    }

    $scenario = [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        dashboardUrl = 'http://127.0.0.1:5173'
        swaggerUrl = 'http://127.0.0.1:8080/swagger-ui.html'
        customer = [ordered]@{ email = $environment['DEMO_USER_EMAIL']; userId = $user.user.userId; walletId = $userWallet.walletId }
        merchant = [ordered]@{ email = $environment['DEMO_MERCHANT_EMAIL']; userId = $merchant.user.userId; walletId = $merchantWallet.walletId }
        operator = [ordered]@{ email = $environment['DEMO_ADMIN_EMAIL']; userId = $admin.user.userId }
        completedPayments = @($completed | ForEach-Object {
            [ordered]@{ paymentId = $_.paymentId; transactionId = $_.transactionId; amountMinor = $_.amount.amountMinor; providerId = $_.providerId }
        })
        heldPayment = [ordered]@{
            paymentId = $held.paymentId
            transactionId = $held.transactionId
            alertId = $alert.alertId
            ruleSummary = $alert.summary
            sagaId = $saga.sagaId
            status = $saga.status
        }
        checks = [ordered]@{
            analyticsVisible = @($analytics.content).Count -ge $paymentIds.Count
            trialBalanceBalanced = $trialBalance.balanced
            accountsAgreeWithJournal = $trialBalance.accountsAgreeWithJournal
            ledgerConsistent = $reconciliation.consistent
            walletLedgerStatus = $liabilityReconciliation.status
            walletLedgerDifferenceMinor = $liabilityReconciliation.differenceMinor
        }
    }
    [System.IO.File]::WriteAllText($script:ScenarioFile, ($scenario | ConvertTo-Json -Depth 12), (New-Object System.Text.UTF8Encoding($false)))
    Write-Pass "Panel scenario saved to $script:ScenarioFile"
    return $scenario
}

# Compose health gates prove liveness, not that the application request path is warm.
# A constrained local JVM pays its whole cold-start cost on the FIRST real request to
# a service: JIT compilation plus the first Hibernate aggregate query plan. Measured
# warm latency for these reads is under a second, but the first call after a cold boot
# can exceed the gateway's 15s response-timeout and surface as a 504. The gateway stops
# waiting while the downstream service still finishes the work, so a bounded retry lands
# on an already-warmed path. Priming here keeps the assertions below measuring
# steady-state behaviour instead of one-time startup cost, without weakening any check.
function Initialize-PanelReadPaths {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [int]$Attempts = 4
    )

    Write-Step 'Priming cold read paths so verification measures steady-state behaviour'
    $paths = @(
        '/api/users/me',
        '/api/admin/analytics/overview',
        '/api/admin/analytics/projection',
        '/api/admin/analytics/payments?size=1',
        '/api/ledger/admin/trial-balance?currency=INR',
        '/api/ledger/admin/reconcile',
        '/api/fraud/alerts?blockingOnly=true&size=1',
        '/api/wallets/admin/reconcile-ledger?currency=INR'
    )

    foreach ($path in $paths) {
        for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
            try {
                Invoke-AegisApi -Method GET -Path $path -Token $Token | Out-Null
                break
            }
            catch {
                if ($attempt -eq $Attempts) {
                    throw "$path did not become responsive after $Attempts warm-up attempts: $($_.Exception.Message)"
                }
                Write-Warn "Cold start on $path (attempt $attempt of $Attempts); still warming, retrying."
            }
        }
    }

    Write-Pass "Primed $($paths.Count) read paths"
}

function Test-PanelScenario {
    Write-Step 'Verifying authentication, prepared scenario, analytics, fraud, orchestration, and ledger integrity'
    $environment = Read-DotEnv
    $admin = Login $environment['DEMO_ADMIN_EMAIL'] $environment['DEMO_ADMIN_PASSWORD']
    Initialize-PanelReadPaths -Token $admin.accessToken
    $profile = Invoke-AegisApi -Method GET -Path '/api/users/me' -Token $admin.accessToken
    if ('ADMIN' -notin @($profile.roles) -or 'FRAUD_ANALYST' -notin @($profile.roles)) {
        throw 'The panel operator account does not have both ADMIN and FRAUD_ANALYST roles.'
    }

    $overview = Invoke-AegisApi -Method GET -Path '/api/admin/analytics/overview' -Token $admin.accessToken
    $projection = Invoke-AegisApi -Method GET -Path '/api/admin/analytics/projection' -Token $admin.accessToken
    $trialBalance = Invoke-AegisApi -Method GET -Path '/api/ledger/admin/trial-balance?currency=INR' -Token $admin.accessToken
    $reconciliation = Invoke-AegisApi -Method GET -Path '/api/ledger/admin/reconcile' -Token $admin.accessToken
    $liabilityReconciliation = Wait-LiabilityReconciliation $admin.accessToken
    if (-not $trialBalance.balanced -or -not $trialBalance.accountsAgreeWithJournal -or -not $reconciliation.consistent) {
        throw 'Ledger verification is not green.'
    }

    $scenarioCheck = $null
    if (Test-Path $script:ScenarioFile) {
        $scenario = Get-Content -Raw $script:ScenarioFile | ConvertFrom-Json
        $heldPayment = Invoke-AegisApi -Method GET -Path "/api/orchestration/payments/$($scenario.heldPayment.paymentId)" -Token $admin.accessToken
        $alerts = Invoke-AegisApi -Method GET -Path '/api/fraud/alerts?blockingOnly=true&size=100' -Token $admin.accessToken
        $alert = @($alerts.content) | Where-Object { $_.paymentId -eq $scenario.heldPayment.paymentId } | Select-Object -First 1
        $payments = Invoke-AegisApi -Method GET -Path '/api/admin/analytics/payments?size=100' -Token $admin.accessToken
        $visibleIds = @($payments.content | ForEach-Object { $_.paymentId })
        $expectedIds = @($scenario.completedPayments | ForEach-Object { $_.paymentId }) + @($scenario.heldPayment.paymentId)
        $missing = @($expectedIds | Where-Object { $_ -notin $visibleIds })
        if ($heldPayment.status -ne 'RISK_HELD' -or $null -eq $alert -or $missing.Count -gt 0) {
            throw 'The prepared panel scenario is incomplete. Run seed to prepare a fresh scenario.'
        }
        $scenarioCheck = [ordered]@{
            heldPaymentId = $scenario.heldPayment.paymentId
            heldSagaStatus = $heldPayment.status
            blockingAlertId = $alert.alertId
            analyticsPaymentsVerified = $expectedIds.Count
        }
    }
    else {
        Write-Warn 'No .panel-demo.json exists; service and ledger checks passed, but no deterministic scenario was verified.'
    }

    $verification = [ordered]@{
        verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
        operator = $profile.email
        roles = @($profile.roles)
        analytics = [ordered]@{
            payments = $overview.payments
            completed = $overview.completed
            onHold = $overview.onHold
            stale = $overview.freshness.stale
            paymentsProjected = $projection.paymentsProjected
        }
        ledger = [ordered]@{
            balanced = $trialBalance.balanced
            differenceMinor = $trialBalance.differenceMinor
            accountsAgreeWithJournal = $trialBalance.accountsAgreeWithJournal
            consistent = $reconciliation.consistent
            findings = @($reconciliation.findings).Count
            walletLedgerStatus = $liabilityReconciliation.status
            walletLedgerDifferenceMinor = $liabilityReconciliation.differenceMinor
        }
        scenario = $scenarioCheck
    }
    [System.IO.File]::WriteAllText($script:VerificationFile, ($verification | ConvertTo-Json -Depth 10), (New-Object System.Text.UTF8Encoding($false)))
    Write-Pass "Panel verification passed; evidence saved to $script:VerificationFile"
    Write-Host ($verification | ConvertTo-Json -Depth 10)
}

function Start-Platform {
    Initialize-Environment
    Write-Step 'Starting the complete local application stack'
    $arguments = @('compose', 'up', '-d')
    if (-not $NoBuild) {
        $arguments += '--build'
    }
    else {
        $arguments += '--no-build'
    }
    Invoke-Docker -Arguments $arguments
    Wait-Compose $TimeoutSeconds
    Wait-Endpoints $TimeoutSeconds
}

function Show-Status {
    Assert-Docker
    Invoke-Docker -Arguments @('compose', 'ps')
    if (Test-Path $script:ScenarioFile) {
        $scenario = Get-Content -Raw $script:ScenarioFile | ConvertFrom-Json
        Write-Host "Prepared held payment: $($scenario.heldPayment.paymentId) / alert $($scenario.heldPayment.alertId)"
    }
    else {
        Write-Warn 'No deterministic panel scenario has been prepared yet.'
    }
}

function Show-Help {
    Write-Host @'
AegisPay panel demonstration helper (Windows PowerShell)

Usage:
  .\scripts\panel-demo.ps1 setup
  .\scripts\panel-demo.ps1 prepare -TimeoutSeconds 1800
  .\scripts\panel-demo.ps1 verify

Actions:
  setup        Generate/validate untracked .env values; do not start containers.
  start        Build, start, and wait for every service and endpoint.
  wait         Wait for existing Compose services and HTTP endpoints.
  seed         Create wallets, 3 completed payments, and 1 impossible-travel HOLD.
  verify       Verify auth, analytics, open fraud HOLD, orchestration, and ledger.
  prepare      setup + start + seed + verify; run this before the panel day.
  status       Show Compose and prepared-scenario status.
  credentials Print local-only customer, merchant, and operator logins.
  stop         Stop containers but keep volumes and prepared business data.
  reset        Delete containers and named volumes. Requires -Force. DESTRUCTIVE.
  help         Show this message.

Use -NoBuild with start/prepare only after images have already been built.
Read PANEL_DEMO.md before presenting.
'@
}

$originalLocation = Get-Location
try {
    Set-Location $script:RepositoryRoot
    switch ($Action) {
        'help' { Show-Help }
        'setup' {
            Assert-Command 'docker'
            Initialize-Environment
            Show-Credentials
        }
        'start' {
            Assert-Docker
            Start-Platform
            Show-Credentials
        }
        'wait' {
            Assert-Docker
            Wait-Compose $TimeoutSeconds
            Wait-Endpoints $TimeoutSeconds
        }
        'seed' {
            Assert-Docker
            Wait-Endpoints $TimeoutSeconds
            New-PanelScenario | Out-Null
            Show-Credentials
        }
        'verify' {
            Assert-Docker
            Wait-Compose $TimeoutSeconds
            Wait-Endpoints $TimeoutSeconds
            Test-PanelScenario
        }
        'prepare' {
            Assert-Docker
            Start-Platform
            New-PanelScenario | Out-Null
            Test-PanelScenario
            Show-Credentials
            Write-Host "`nPanel preparation is complete. Keep the stack warm and follow PANEL_DEMO.md." -ForegroundColor Green
        }
        'status' { Show-Status }
        'credentials' { Show-Credentials }
        'stop' {
            Assert-Docker
            Invoke-Docker -Arguments @('compose', 'down')
            Write-Pass 'Containers stopped; volumes and panel data retained'
        }
        'reset' {
            Assert-Docker
            if (-not $Force) {
                throw 'reset deletes all local AegisPay volumes and demo data. Re-run with -Force to confirm.'
            }
            Invoke-Docker -Arguments @('compose', 'down', '--volumes', '--remove-orphans')
            Remove-Item $script:ScenarioFile, $script:VerificationFile -Force -ErrorAction SilentlyContinue
            Write-Pass 'Local containers, volumes, and generated panel evidence were removed. .env was retained.'
        }
    }
}
finally {
    Set-Location $originalLocation
}
