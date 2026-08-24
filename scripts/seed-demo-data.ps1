<#
.SYNOPSIS
    Seed the demo environment: two registered users, a bank, two accounts,
    two VPAs, and an opening balance.

.DESCRIPTION
    Re-runnable: every run uses a fresh suffix, so it never collides with a
    previous run.

    The payer is a real registered user with an MPIN, and the payer VPA is
    registered against that user's id. That matters: the orchestrator verifies
    that whoever initiates a payment actually owns the VPA it debits, so a VPA
    registered to an arbitrary id is rejected with 403.

    Prerequisites: psp-service (8082), vpa-service (8081), bank-service (8084).

.EXAMPLE
    .\scripts\seed-demo-data.ps1
#>
param(
    [string]$PspUrl  = "http://localhost:8082",
    [string]$VpaUrl  = "http://localhost:8081",
    [string]$BankUrl = "http://localhost:8084",
    [string]$Suffix
)

$ErrorActionPreference = 'Stop'

if (-not $Suffix) {
    $Suffix = ([string][int][double]::Parse((Get-Date -UFormat %s))).Substring(6)
}

# Invoke-RestMethod throws on a non-2xx status and swallows the response body,
# which is exactly the information needed to explain a failure. This wrapper
# surfaces it instead.
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        $Body
    )
    try {
        $json = if ($null -ne $Body) { $Body | ConvertTo-Json -Compress } else { $null }
        if ($json) {
            return Invoke-RestMethod -Method $Method -Uri $Uri `
                -ContentType 'application/json' -Body $json
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri
    } catch {
        $detail = ""
        if ($_.Exception.Response) {
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $detail = $reader.ReadToEnd()
            } catch { }
        }
        Write-Host ""
        Write-Host "FAILED: $Method $Uri" -ForegroundColor Red
        if ($detail) { Write-Host $detail -ForegroundColor Red }
        else { Write-Host $_.Exception.Message -ForegroundColor Red }
        Write-Host ""
        Write-Host "Is the service running? Expected:" -ForegroundColor Yellow
        Write-Host "  psp-service  $PspUrl" -ForegroundColor Yellow
        Write-Host "  vpa-service  $VpaUrl" -ForegroundColor Yellow
        Write-Host "  bank-service $BankUrl" -ForegroundColor Yellow
        exit 1
    }
}

# register -> setup MPIN. Two steps because the PSP separates identity from
# credential: a user exists before they can transact, and the account sits in
# PENDING_MPIN until an MPIN is set.
function Register-User {
    param([string]$Mobile, [string]$Device)

    $resp = Invoke-Api -Method Post -Uri "$PspUrl/api/v1/auth/register" -Body @{
        mobileNumber      = $Mobile
        deviceId          = $Device
        deviceFingerprint = "fp-$Suffix"
    }
    Invoke-Api -Method Post -Uri "$PspUrl/api/v1/auth/setup-mpin" -Body @{
        userId = $resp.userId
        mpin   = "1234"
    } | Out-Null
    return $resp.userId
}

# +91 followed by exactly 10 digits. psp-service normalises to E.164 and
# rejects anything longer, which is correct for an Indian mobile number.
$payerMobile = "+9198${Suffix}0001"
$payeeMobile = "+9198${Suffix}0002"
$payerDevice = "demo-device-payer-$Suffix"
$payeeDevice = "demo-device-payee-$Suffix"

Write-Host "==> Registering payer"
$payerUser = Register-User -Mobile $payerMobile -Device $payerDevice
Write-Host "    userId=$payerUser"

Write-Host "==> Registering payee"
$payeeUser = Register-User -Mobile $payeeMobile -Device $payeeDevice
Write-Host "    userId=$payeeUser"

Write-Host "==> Creating bank"
$bank = Invoke-Api -Method Post -Uri "$BankUrl/banks" -Body @{
    name      = "Axis Bank $Suffix"
    upiHandle = "okaxis$Suffix"
}
$bankId = $bank.data.bankId
$ifsc   = $bank.data.ifscCode
Write-Host "    bankId=$bankId ifsc=$ifsc"

Write-Host "==> Creating accounts"
$payerAcc = (Invoke-Api -Method Post -Uri "$BankUrl/accounts" -Body @{
    userId = $payerUser; bankId = $bankId; primary = $true
}).data.accountNumber

$payeeAcc = (Invoke-Api -Method Post -Uri "$BankUrl/accounts" -Body @{
    userId = $payeeUser; bankId = $bankId; primary = $true
}).data.accountNumber
Write-Host "    payer=$payerAcc  payee=$payeeAcc"

# Opening balance goes through the same idempotent posting endpoint that
# payments use. Nothing special-cased, and it exercises the idempotency guard
# on the way past.
Write-Host "==> Funding payer with 10000.00"
Invoke-Api -Method Post -Uri "$BankUrl/accounts/$payerAcc/credit" -Body @{
    txId   = "00000000-0000-0000-0000-00000000$Suffix"
    leg    = "CREDIT"
    amount = 10000.00
    rrn    = "OPENING"
} | Out-Null
Write-Host "    done"

Write-Host "==> Registering VPAs"
Invoke-Api -Method Post -Uri "$VpaUrl/api/v1/vpa" -Body @{
    vpaAddress        = "arjun$Suffix@okaxis"
    userId            = $payerUser
    accountNumber     = $payerAcc
    ifscCode          = $ifsc
    accountHolderName = "Arjun Mehta"
} | Out-Null

Invoke-Api -Method Post -Uri "$VpaUrl/api/v1/vpa" -Body @{
    vpaAddress        = "priya$Suffix@okaxis"
    userId            = $payeeUser
    accountNumber     = $payeeAcc
    ifscCode          = $ifsc
    accountHolderName = "Priya Sharma"
} | Out-Null

Write-Host ""
Write-Host "=================================================================="
Write-Host " PAYER   $payerMobile  mpin 1234  device $payerDevice"
Write-Host " VPA     arjun$Suffix@okaxis  -> $payerAcc  (balance 10000.00)"
Write-Host " PAYEE   VPA priya$Suffix@okaxis  -> $payeeAcc"
Write-Host " BANK    $bankId / $ifsc"
Write-Host "=================================================================="
Write-Host ""
Write-Host "Sign in to the showcase with:" -ForegroundColor Green
Write-Host "   mobile  $payerMobile"
Write-Host "   device  $payerDevice"
Write-Host "   MPIN    1234"
Write-Host ""
Write-Host "Payer VPA:  arjun$Suffix@okaxis"
Write-Host "Payee VPA:  priya$Suffix@okaxis"
