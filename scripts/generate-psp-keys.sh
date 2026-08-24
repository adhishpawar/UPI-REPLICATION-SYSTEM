#!/usr/bin/env bash
# Generate the RSA key pair that psp-service signs JWTs with.
#
# WHY THIS SCRIPT EXISTS
#
# psp-service reads `keys/private_key_pkcs8.pem` and `keys/public_key.pem` from
# its classpath, and `.gitignore` correctly excludes both -- a private signing
# key must never be committed. But with no way to regenerate them, the service
# could not be started from a fresh clone: it failed at bean creation with a
# FileNotFoundException and no indication of what was missing.
#
# "Secrets are not in the repository" is only half a secrets strategy. The
# other half is a documented, repeatable way to obtain them.
#
# The work is done by GenerateKeys.java, run directly by the JDK. The first
# version of this script shelled out to `openssl`, which turned out to be
# present only because Git for Windows bundles it -- so the bootstrap worked in
# Git Bash and failed in PowerShell. A JDK is already a hard requirement, so
# using it here means one command that behaves the same in every shell.
#
# PowerShell equivalent: scripts\generate-psp-keys.ps1
#
# In a real deployment these keys come from a KMS or secret manager, are
# rotated on a schedule, and the private key never exists as a file. The JWKS
# endpoint already publishes a `kid`, so rotation is possible without changing
# any verifying service.

set -euo pipefail

DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/psp-service/src/main/resources/keys}"

if ! command -v java > /dev/null 2>&1; then
  echo "java is not on PATH. This project needs JDK 21; nothing runs without it." >&2
  exit 1
fi

exec java "$(dirname "$0")/GenerateKeys.java" "$DIR"
