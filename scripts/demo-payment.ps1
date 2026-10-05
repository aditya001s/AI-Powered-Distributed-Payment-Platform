<#
.SYNOPSIS
    Makes a live payment against the running AegisPay stack, for demonstrating the
    system to someone watching.

.DESCRIPTION
    The dashboard at http://127.0.0.1:5173 is an operator/analyst console and is
    deliberately read-only for payments, so there is no "new payment" button. Payments
    are created through the API, which is what this script does, using the same demo
    accounts and the same risk-context fields the fraud engine scores on.

    Two modes:

      (default)  One ordinary payment that should settle to COMPLETED via a simulated
                 provider. Prints the customer's wallet balance before and after so the
                 debit is visible.

      -Fraud     Submits the same device from implausibly distant cities in quick
                 succession to trip the impossible-travel rule, producing a payment
                 parked at RISK_HELD with an open blocking alert. The loop is
                 outcome-based: it retries up to three times and only reports a hold it
                 actually observed. Use this to show the fraud queue, then release or
                 block the payment from the Alerts page in the dashboard.

    No real money moves anywhere. Providers are simulated.

.PARAMETER Amount
    Payment amount in INR. Defaults to 3.00.

.PARAMETER Fraud
    Trigger the impossible-travel fraud hold instead of a clean payment.

.EXAMPLE
    .\scripts\demo-payment.ps1
    .\scripts\demo-payment.ps1 -Amount 25
    .\scripts\demo-payment.ps1 -Fraud
#>
param(
    [decimal]$Amount = 3.00,
    [switch]$Fraud
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$script:BaseUrl = 'http://127.0.0.1:8080'
# A constrained local JVM is slow on the first call through each path, so the client
# stays patient. This is only the demo client's timeout; nothing in the services changes.
$script:Timeout = 90

function Write-Step($Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Pass($Message) { Write-Host "[OK]   $Message" -ForegroundColor Green }
function Write-Info($Message) { Write-Host "       $Message" -ForegroundColor Gray }
function Write-Hold($Message) { Write-Host "[HELD] $Message" -ForegroundColor Yellow }

function Read-DotEnv {
    if (-not (Test-Path '.env')) {
        throw "No .env file found. Run .\scripts\panel-demo.ps1 setup first."
    }
    $values = @{}
    Get-Content '.env' | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
    }
    return $values
}

function Invoke-Api {
    param(
        [ValidateSet('GET', 'POST')][string]$Method = 'GET',
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Token,
        [object]$Body,
        [hashtable]$Headers = @{}
    )
    $requestHeaders = @{}
    foreach ($entry in $Headers.GetEnumerator()) { $requestHeaders[$entry.Key] = $entry.Value }
    if ($Token) { $requestHeaders['Authorization'] = "Bearer $Token" }

    $parameters = @{
        Uri         = "$script:BaseUrl$Path"
        Method      = $Method
        Headers     = $requestHeaders
        TimeoutSec  = $script:Timeout
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Login([string]$Email, [string]$Password) {
    $response = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ email = $Email; password = $Password }
    if (-not $response.accessToken) { throw "Login failed for $Email." }
    return $response
}

function Get-InrWallet([string]$Token) {
    # Windows PowerShell 5 can hand back a JSON array as a single scalar across a
    # function boundary, so force pipeline enumeration before filtering.
    $response = Invoke-Api -Path '/api/wallets' -Token $Token
    $wallets = if ($null -eq $response) { @() } else { @($response | ForEach-Object { $_ }) }
    $wallet = $wallets | Where-Object { $_.currency -eq 'INR' } | Select-Object -First 1
    if ($null -eq $wallet) {
        throw "No INR wallet exists yet. Run .\scripts\panel-demo.ps1 seed first."
    }
    return $wallet
}

function Get-Balance([string]$Token, [string]$WalletId) {
    return [long](Invoke-Api -Path "/api/wallets/$WalletId" -Token $Token).available.amountMinor
}

function Submit-DemoPayment {
    param(
        [string]$Token,
        [string]$DestinationWalletId,
        [decimal]$Value,
        [string]$Description,
        [string]$IdempotencyKey,
        [hashtable]$RiskContext
    )
    # Retrying with the same Idempotency-Key is safe by the endpoint's own guarantee:
    # it either accepts the payment once or returns the already-accepted one.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            return Invoke-Api -Method POST -Path '/api/payments' -Token $Token `
                -Headers @{ 'Idempotency-Key' = $IdempotencyKey } -Body @{
                    destinationWalletId = $DestinationWalletId
                    amount              = $Value
                    currency            = 'INR'
                    paymentType         = 'P2P_TRANSFER'
                    description         = $Description
                    riskContext         = $RiskContext
                }
        }
        catch {
            $transient = $_.Exception.Message -match 'timed out|timeout|\(50[234]\)'
            if ($attempt -eq 3 -or -not $transient) { throw }
            Write-Info "Attempt $attempt did not return in time while services warm; retrying."
            Start-Sleep -Seconds 5
        }
    }
}

# Same device, implausibly far apart. That pairing is what the impossible-travel rule scores.
$mumbai = @{
    deviceId          = 'demo-device-a'
    deviceFingerprint = 'demo-fingerprint-a'
    countryCode       = 'IN'
    city              = 'Mumbai'
    latitude          = 19.0760
    longitude         = 72.8777
    channel           = 'MOBILE_ANDROID'
}
$newYork = @{
    deviceId          = 'demo-device-a'
    deviceFingerprint = 'demo-fingerprint-a'
    countryCode       = 'US'
    city              = 'New York'
    latitude          = 40.7128
    longitude         = -74.0060
    channel           = 'MOBILE_ANDROID'
}

$environment = Read-DotEnv

Write-Step 'Signing in as the demo customer and merchant'
$user = Login $environment['DEMO_USER_EMAIL'] $environment['DEMO_USER_PASSWORD']
$merchant = Login $environment['DEMO_MERCHANT_EMAIL'] $environment['DEMO_MERCHANT_PASSWORD']
$admin = Login $environment['DEMO_ADMIN_EMAIL'] $environment['DEMO_ADMIN_PASSWORD']
Write-Pass 'Authenticated'

$customerWallet = Get-InrWallet $user.accessToken
$merchantWallet = Get-InrWallet $merchant.accessToken
$before = Get-Balance $user.accessToken $customerWallet.walletId
Write-Info "customer wallet $($customerWallet.walletId), balance $before minor units"
Write-Info "merchant wallet $($merchantWallet.walletId)"

$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss')

if (-not $Fraud) {
    Write-Step "Submitting a $Amount INR payment"
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $accepted = Submit-DemoPayment -Token $user.accessToken -DestinationWalletId $merchantWallet.walletId `
        -Value $Amount -Description 'Live demonstration payment' `
        -IdempotencyKey "demo-$runId" -RiskContext $mumbai
    Write-Pass "Accepted as $($accepted.paymentId) with status $($accepted.status)"

    Write-Step 'Waiting for the saga to settle it through wallet, ledger, fraud, and a provider'
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
    $payment = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $payment = Invoke-Api -Path "/api/payments/$($accepted.paymentId)" -Token $user.accessToken
        if ($payment.status -in @('COMPLETED', 'FAILED', 'REJECTED')) { break }
        Start-Sleep -Seconds 3
    }
    $clock.Stop()

    if ($payment.status -ne 'COMPLETED') {
        Write-Hold "Payment ended as $($payment.status). If it is held, run with -Fraud to see the alert flow, or check the Alerts page."
        exit 1
    }

    $after = Get-Balance $user.accessToken $customerWallet.walletId
    Write-Pass "COMPLETED via $($payment.providerId) in $($clock.Elapsed.TotalSeconds.ToString('F1'))s"
    Write-Info "balance $before -> $after minor units (debited $($before - $after))"
    Write-Host ''
    Write-Host 'Show it in the dashboard: http://127.0.0.1:5173 -> Payments' -ForegroundColor White
    Write-Host "Payment id: $($payment.paymentId)" -ForegroundColor White
    exit 0
}

Write-Step 'Submitting the same device from distant cities to trip the impossible-travel rule'
$held = $null
for ($attempt = 1; $attempt -le 3 -and $null -eq $held; $attempt++) {
    Start-Sleep -Seconds (2 * $attempt)
    $context = if ($attempt % 2 -eq 1) { $newYork } else { $mumbai }
    Write-Info "attempt $attempt from $($context.city)"
    $accepted = Submit-DemoPayment -Token $user.accessToken -DestinationWalletId $merchantWallet.walletId `
        -Value $Amount -Description "Impossible-travel demonstration attempt $attempt" `
        -IdempotencyKey "demo-$runId-fraud-$attempt" -RiskContext $context

    # A held payment is parked in the saga, not reported as a payment-level status, so
    # the hold is read from the orchestration API rather than waited for as an event.
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $payment = Invoke-Api -Path "/api/payments/$($accepted.paymentId)" -Token $user.accessToken
        if ($payment.status -eq 'COMPLETED') {
            Write-Info "attempt $attempt completed normally; trying a farther jump"
            break
        }
        try {
            $saga = Invoke-Api -Path "/api/orchestration/payments/$($accepted.paymentId)" -Token $admin.accessToken
            if ($saga.status -eq 'RISK_HELD') { $held = $accepted; break }
        }
        catch { }
        if ($payment.status -in @('FAILED', 'REJECTED')) { break }
        Start-Sleep -Seconds 3
    }
}

if ($null -eq $held) {
    throw 'Could not produce an impossible-travel hold in three attempts. Try running it again.'
}

Write-Hold "$($held.paymentId) is parked at RISK_HELD"

Write-Step 'Locating the blocking fraud alert'
$alert = $null
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
while ([DateTimeOffset]::UtcNow -lt $deadline -and $null -eq $alert) {
    $page = Invoke-Api -Path '/api/fraud/alerts?blockingOnly=true&size=100' -Token $admin.accessToken
    $alert = @($page.content) | Where-Object { $_.paymentId -eq $held.paymentId } | Select-Object -First 1
    if ($null -eq $alert) { Start-Sleep -Seconds 3 }
}
if ($null -eq $alert) { throw "The hold exists but no blocking alert appeared for $($held.paymentId)." }

Write-Pass "Blocking alert $($alert.alertId), decision $($alert.decision)"
Write-Host ''
Write-Host 'Now show the analyst workflow in the dashboard:' -ForegroundColor White
Write-Host '  1. http://127.0.0.1:5173 -> sign in as the operator account' -ForegroundColor White
Write-Host '  2. Alerts -> claim the alert, then Release or Block it' -ForegroundColor White
Write-Host '  3. Risk and Overview show the score and the updated totals' -ForegroundColor White
Write-Host ''
Write-Host "Payment id: $($held.paymentId)" -ForegroundColor White
Write-Host "Alert id:   $($alert.alertId)" -ForegroundColor White
Write-Host "Operator:   $($environment['DEMO_ADMIN_EMAIL'])" -ForegroundColor White
