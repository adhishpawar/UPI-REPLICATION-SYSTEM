<#
.SYNOPSIS
    Generate the RSA key pair that psp-service signs JWTs with.

.DESCRIPTION
    psp-service reads keys/private_key_pkcs8.pem and keys/public_key.pem from
    its classpath. Both are gitignored -- a private signing key must never be
    committed -- so this script is how you obtain them.

    "Secrets are not in the repository" is only half a secrets strategy. The
    other half is a documented, repeatable way to get them; without it the
    service cannot start from a fresh clone.

    The work is done by scripts/GenerateKeys.java, run directly by the JDK.
    That keeps this script and its bash twin identical in behaviour, and avoids
    depending on openssl -- which exists on Windows only if Git for Windows
    happens to be installed.

    In a real deployment these keys come from a KMS or secret manager, are
    rotated on a schedule, and the private key never exists as a file. The JWKS
    endpoint already publishes a `kid`, so rotation is possible without
    changing any verifying service.

.PARAMETER KeyDir
    Where to write the keys. Defaults to psp-service's resources/keys.

.EXAMPLE
    .\scripts\generate-psp-keys.ps1
#>
param(
    [string]$KeyDir
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $KeyDir) {
    $KeyDir = Join-Path $repoRoot "psp-service\src\main\resources\keys"
}

$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) {
    Write-Error "java is not on PATH. This project needs JDK 21; nothing runs without it."
    exit 1
}

& $java (Join-Path $PSScriptRoot "GenerateKeys.java") $KeyDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "Key generation failed."
    exit $LASTEXITCODE
}
