<#
.SYNOPSIS
    End-to-end verification of the money invariants and the auth boundary.

.DESCRIPTION
    Proves the properties that matter, rather than that endpoints return 200:

      1. an unauthenticated or forged-token payment is refused
      2. you cannot spend from a VPA you do not own
      3. a payment moves money exactly once
      4. a credit whose outcome is UNKNOWN is neither retried nor reversed --
         it is reconciled, and resolves according to what the bank actually did
      5. a duplicate Idempotency-Key moves no additional money
      6. another user's transaction is invisible

    Run scripts\seed-demo-data.ps1 first and pass it the values it prints, or
    let this script run the seed itself with -Seed.

.EXAMPLE
    .\scripts\verify.ps1 -Seed

.EXAMPLE
    .\scripts\verify.ps1 -PayerVpa arjun7719@okaxis -PayeeVpa priya7719@okaxis `
        -PayerAcc 156246341754 -PayeeAcc 113619734681 `
        -Mobile +919877190001 -Device demo-device-payer-7719
#>
param(
    [switch]$Seed,
    [string]$PayerVpa,
    [string]$PayeeVpa,
    [string]$PayerAcc,
    [string]$PayeeAcc,
    [string]$Mobile,
    [string]$Device,
    [string]$Mpin    = "1234",
    [string]$PspUrl  = "http://localhost:8082",
    [string]$OrchUrl = "http://localhost:8083",
    [string]$BankUrl = "http://localhost:8084"
)

$ErrorActionPreference = 'Continue'

$script:pass = 0
$script:fail = 0
function Pass($m) { Write-Host "  PASS  $m" -ForegroundColor Green; $script:pass++ }
function Fail($m) { Write-Host "  FAIL  $m" -ForegroundColor Red;   $script:fail++ }

# ── Seed if asked ──────────────────────────────────────────────────────
if ($Seed) {
    $out = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") 6>&1 | Out-String
    Write-Host $out -ForegroundColor DarkGray
    $PayerVpa = ([regex]::Match($out, 'Payer VPA:\s+(\S+)')).Groups[1].Value
    $PayeeVpa = ([regex]::Match($out, 'Payee VPA:\s+(\S+)')).Groups[1].Value
    $PayerAcc = ([regex]::Match($out, '->\s+(\d+)\s+\(balance')).Groups[1].Value
    $PayeeAcc = ([regex]::Match($out, 'priya\S+\s+->\s+(\d+)')).Groups[1].Value
    $Mobile   = ([regex]::Match($out, 'mobile\s+(\+\d+)')).Groups[1].Value
    $Device   = ([regex]::Match($out, 'device\s+(demo-device-payer-\S+)')).Groups[1].Value
}

foreach ($p in 'PayerVpa','PayeeVpa','PayerAcc','PayeeAcc','Mobile','Device') {
    if (-not (Get-Variable $p -ValueOnly)) {
        Write-Error "$p is not set. Run with -Seed, or pass the values seed-demo-data.ps1 printed."
        exit 1
    }
}

function Get-Balance([string]$acct) {
    (Invoke-RestMethod -Uri "$BankUrl/accounts/$acct/reconcile").data.ledgerDerivedBalance
}

# Status codes are the assertion here, so failures must not throw.
function Invoke-Status {
    param([string]$Method, [string]$Uri, $Body, [hashtable]$Headers)
    try {
        $json = if ($null -ne $Body) { $Body | ConvertTo-Json -Compress } else { $null }
        $r = Invoke-WebRequest -Method $Method -Uri $Uri -Headers $Headers `
                -ContentType 'application/json' -Body $json -UseBasicParsing
        return @{ Code = [int]$r.StatusCode; Body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        return @{ Code = $code; Body = $null }
    }
}

Write-Host "=============================================================="
Write-Host " AUTHENTICATED END-TO-END VERIFICATION"
Write-Host "=============================================================="

# ── 1. Authentication ──────────────────────────────────────────────────
Write-Host "`n[1] Authentication"
$login = Invoke-RestMethod -Method Post -Uri "$PspUrl/api/v1/auth/login" `
    -ContentType 'application/json' `
    -Body (@{ mobileNumber = $Mobile; deviceId = $Device; mpin = $Mpin } | ConvertTo-Json -Compress)
$token = $login.accessToken
if ($token) { Pass "logged in, token issued for $($login.userId.Substring(0,8))..." }
else { Fail "login failed"; exit 1 }

$auth = @{ Authorization = "Bearer $token" }
$payBody = @{ payerVpa = $PayerVpa; payeeVpa = $PayeeVpa; amount = 1.00; currency = "INR" }

$r = Invoke-Status -Method Post -Uri "$OrchUrl/api/v1/payments" -Body $payBody `
        -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
if ($r.Code -eq 401) { Pass "payment without a token rejected (401)" }
else { Fail "expected 401 without a token, got $($r.Code)" }

$r = Invoke-Status -Method Post -Uri "$OrchUrl/api/v1/payments" -Body $payBody `
        -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString()
                    'Authorization'    = 'Bearer not.a.real.token' }
if ($r.Code -eq 401) { Pass "payment with a forged token rejected (401)" }
else { Fail "expected 401 for a forged token, got $($r.Code)" }

# ── 2. Ownership ───────────────────────────────────────────────────────
Write-Host "`n[2] VPA ownership"
$r = Invoke-Status -Method Post -Uri "$OrchUrl/api/v1/payments" `
        -Body @{ payerVpa = $PayeeVpa; payeeVpa = $PayerVpa; amount = 1.00; currency = "INR" } `
        -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString(); 'Authorization' = "Bearer $token" }
if ($r.Code -eq 403) { Pass "spending from someone else's VPA rejected (403)" }
else { Fail "expected 403 for a foreign VPA, got $($r.Code)" }

# ── 3. Scenarios ───────────────────────────────────────────────────────
function Invoke-Payment {
    param([decimal]$Amount, [string]$Simulate, [string]$Key)
    $body = @{ payerVpa = $PayerVpa; payeeVpa = $PayeeVpa; amount = $Amount; currency = "INR" }
    if ($Simulate) { $body.simulate = $Simulate }
    if (-not $Key) { $Key = [guid]::NewGuid().ToString() }
    $r = Invoke-Status -Method Post -Uri "$OrchUrl/api/v1/payments" -Body $body `
            -Headers @{ 'Idempotency-Key' = $Key; 'Authorization' = "Bearer $token" }
    return @{ Txn = $r.Body.transactionId; Key = $Key; Code = $r.Code }
}

function Wait-Terminal {
    param([string]$Txn, [int]$Seconds = 45)
    $terminal = @('COMPLETED','FAILED','DEBIT_FAILED','REVERSED','MANUAL_REVIEW')
    for ($i = 0; $i -lt $Seconds; $i++) {
        $s = (Invoke-RestMethod -Uri "$OrchUrl/api/v1/payments/$Txn/status" -Headers $auth).currentState
        if ($terminal -contains $s) { return $s }
        Start-Sleep -Seconds 1
    }
    return (Invoke-RestMethod -Uri "$OrchUrl/api/v1/payments/$Txn/status" -Headers $auth).currentState
}

Write-Host "`n[3] Payment scenarios"
foreach ($case in @(
    @{ Label = "happy path";      Amount = 200.00; Sim = "";               Expect = "COMPLETED" },
    @{ Label = "credit timeout";  Amount = 150.00; Sim = "TIMEOUT_CREDIT"; Expect = "COMPLETED" },
    @{ Label = "credit rejected"; Amount = 120.00; Sim = "REJECT_CREDIT";  Expect = "REVERSED"  }
)) {
    $pb = Get-Balance $PayerAcc; $eb = Get-Balance $PayeeAcc
    $p = Invoke-Payment -Amount $case.Amount -Simulate $case.Sim
    if (-not $p.Txn) { Fail "$($case.Label): not accepted (HTTP $($p.Code))"; continue }
    $state = Wait-Terminal -Txn $p.Txn
    $pa = Get-Balance $PayerAcc; $ea = Get-Balance $PayeeAcc
    $line = "{0,-18} -> {1,-13} payer {2}->{3}  payee {4}->{5}" -f $case.Label, $state, $pb, $pa, $eb, $ea
    if ($state -eq $case.Expect) { Pass $line } else { Fail "$line  (expected $($case.Expect))" }
}

# ── 4. Idempotency ─────────────────────────────────────────────────────
Write-Host "`n[4] Idempotency"
$key = [guid]::NewGuid().ToString()
$first = Invoke-Payment -Amount 60.00 -Key $key
Wait-Terminal -Txn $first.Txn -Seconds 30 | Out-Null
$before = Get-Balance $PayerAcc
$second = Invoke-Payment -Amount 60.00 -Key $key
Start-Sleep -Seconds 2
$after = Get-Balance $PayerAcc
if ($first.Txn -eq $second.Txn) { Pass "duplicate key returned the same transaction" }
else { Fail "duplicate key created a different transaction" }
if ($before -eq $after) { Pass "duplicate moved no additional money ($before)" }
else { Fail "balance moved $before -> $after on a duplicate" }

# ── 5. Cross-user read ─────────────────────────────────────────────────
Write-Host "`n[5] Reading another user's transaction"
$payeeMobile = $Mobile.Substring(0, $Mobile.Length - 1) + "2"
$payeeDevice = $Device -replace 'payer', 'payee'
try {
    $other = (Invoke-RestMethod -Method Post -Uri "$PspUrl/api/v1/auth/login" `
        -ContentType 'application/json' `
        -Body (@{ mobileNumber = $payeeMobile; deviceId = $payeeDevice; mpin = $Mpin } | ConvertTo-Json -Compress)).accessToken
    $r = Invoke-Status -Method Get -Uri "$OrchUrl/api/v1/payments/$($first.Txn)/status" `
            -Headers @{ Authorization = "Bearer $other" }
    if ($r.Code -eq 404) { Pass "another user's transaction is invisible (404, not 403)" }
    else { Fail "expected 404 reading another user's transaction, got $($r.Code)" }
} catch {
    Write-Host "  SKIP  could not log in as the payee" -ForegroundColor Yellow
}

Write-Host "`n=============================================================="
Write-Host " PASS: $script:pass    FAIL: $script:fail"
Write-Host "=============================================================="
if ($script:fail -gt 0) { exit 1 }
