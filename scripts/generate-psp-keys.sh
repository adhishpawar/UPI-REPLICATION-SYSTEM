#!/usr/bin/env bash
# Generate the RSA key pair that psp-service signs JWTs with.
#
# WHY THIS SCRIPT EXISTS
#
# psp-service reads `keys/private_key_pkcs8.pem` and `keys/public_key.pem` from
# its classpath, and `.gitignore` correctly excludes both -- a private signing
# key must never be committed. But with no way to regenerate them, the service
# simply could not be started from a fresh clone: it failed at bean creation
# with a FileNotFoundException and no indication of what was missing.
#
# "Secrets are not in the repository" is only half a secrets strategy. The
# other half is a documented, repeatable way to obtain them. This script is
# that half for local development.
#
# In a real deployment these keys come from a KMS or secret manager, are
# rotated on a schedule, and the private key never exists as a file on disk at
# all. The JWKS endpoint already publishes a `kid`, so rotation is possible
# without changing any verifying service.

set -euo pipefail

DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/psp-service/src/main/resources/keys}"
mkdir -p "$DIR"

if [ -f "$DIR/private_key_pkcs8.pem" ]; then
  echo "Keys already exist in $DIR -- leaving them alone."
  echo "Delete them first if you intend to rotate."
  exit 0
fi

echo "==> Generating RSA-2048 signing key in $DIR"

# PKCS#8 is what Java's PKCS8EncodedKeySpec expects. openssl's default for
# `genrsa` is PKCS#1 ("BEGIN RSA PRIVATE KEY"), which Java cannot read without
# conversion -- hence `genpkey` rather than `genrsa`.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$DIR/private_key_pkcs8.pem" 2>/dev/null

# X.509 SubjectPublicKeyInfo, which is what X509EncodedKeySpec expects.
openssl rsa -pubout -in "$DIR/private_key_pkcs8.pem" \
  -out "$DIR/public_key.pem" 2>/dev/null

chmod 600 "$DIR/private_key_pkcs8.pem" 2>/dev/null || true

echo "    private_key_pkcs8.pem  (gitignored -- keep it that way)"
echo "    public_key.pem"
echo
echo "Both are excluded by psp-service/.gitignore. Do not commit either."
