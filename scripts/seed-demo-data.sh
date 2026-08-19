#!/usr/bin/env bash
# Seed the demo environment: one bank, two accounts, two VPAs, opening balance.
#
# Re-runnable: every run creates a fresh bank/accounts/VPAs with a new suffix,
# so it never collides with a previous run.
#
# Prerequisites: vpa-service (8081) and bank-service (8084) running.

set -euo pipefail

BANK_URL="${BANK_URL:-http://localhost:8084}"
VPA_URL="${VPA_URL:-http://localhost:8081}"
SUFFIX="${SUFFIX:-$(date +%s | tail -c 5)}"

jsonval() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# Fail loudly rather than carrying an empty identifier forward. A seed script
# that silently produces blank ids yields a "successful" run in which every
# subsequent request is malformed -- and the real failure then surfaces much
# later, looking like something else entirely.
require() {
  if [ -z "${2:-}" ]; then
    echo "FAILED: could not read $1 from the response below" >&2
    echo "${3:-<no response>}" >&2
    exit 1
  fi
}

echo "==> Creating bank"
BANK_JSON=$(curl -s -X POST "$BANK_URL/banks" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Axis Bank $SUFFIX\",\"upiHandle\":\"okaxis$SUFFIX\"}")
BANK_ID=$(echo "$BANK_JSON" | jsonval bankId)
IFSC=$(echo "$BANK_JSON" | jsonval ifscCode)
require bankId "$BANK_ID" "$BANK_JSON"
require ifscCode "$IFSC" "$BANK_JSON"
echo "    bankId=$BANK_ID ifsc=$IFSC"

echo "==> Creating payer account"
PAYER_JSON=$(curl -s -X POST "$BANK_URL/accounts" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"user-arjun\",\"bankId\":\"$BANK_ID\",\"primary\":true}")
PAYER_ACC=$(echo "$PAYER_JSON" | jsonval accountNumber)
require "payer accountNumber" "$PAYER_ACC" "$PAYER_JSON"
echo "    payer account=$PAYER_ACC"

echo "==> Creating payee account"
PAYEE_JSON=$(curl -s -X POST "$BANK_URL/accounts" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"user-priya\",\"bankId\":\"$BANK_ID\",\"primary\":true}")
PAYEE_ACC=$(echo "$PAYEE_JSON" | jsonval accountNumber)
require "payee accountNumber" "$PAYEE_ACC" "$PAYEE_JSON"
echo "    payee account=$PAYEE_ACC"

# Opening balances go through the same idempotent posting endpoint that
# payments use. Nothing special-cased, and it exercises the idempotency guard
# on the way past.
echo "==> Funding payer with 10000.00"
curl -s -X POST "$BANK_URL/accounts/$PAYER_ACC/credit" -H 'Content-Type: application/json' \
  -d "{\"txId\":\"00000000-0000-0000-0000-00000000${SUFFIX}\",\"leg\":\"CREDIT\",\"amount\":10000.00,\"rrn\":\"OPENING\"}" \
  > /dev/null
echo "    done"

echo "==> Registering VPAs"
VPA_A=$(curl -s -X POST "$VPA_URL/api/v1/vpa" -H 'Content-Type: application/json' \
  -d "{\"vpaAddress\":\"arjun${SUFFIX}@okaxis\",\"userId\":\"11111111-1111-1111-1111-111111111111\",\"accountNumber\":\"$PAYER_ACC\",\"ifscCode\":\"$IFSC\",\"accountHolderName\":\"Arjun Mehta\"}")
require "payer VPA" "$(echo "$VPA_A" | jsonval vpaAddress)" "$VPA_A"

VPA_B=$(curl -s -X POST "$VPA_URL/api/v1/vpa" -H 'Content-Type: application/json' \
  -d "{\"vpaAddress\":\"priya${SUFFIX}@okaxis\",\"userId\":\"22222222-2222-2222-2222-222222222222\",\"accountNumber\":\"$PAYEE_ACC\",\"ifscCode\":\"$IFSC\",\"accountHolderName\":\"Priya Sharma\"}")
require "payee VPA" "$(echo "$VPA_B" | jsonval vpaAddress)" "$VPA_B"

echo
echo "=================================================="
echo " PAYER VPA   : arjun${SUFFIX}@okaxis   ($PAYER_ACC)"
echo " PAYEE VPA   : priya${SUFFIX}@okaxis   ($PAYEE_ACC)"
echo " BANK / IFSC : $BANK_ID / $IFSC"
echo "=================================================="
echo
echo "Export these for the smoke test:"
echo "  export PAYER_VPA=arjun${SUFFIX}@okaxis PAYEE_VPA=priya${SUFFIX}@okaxis"
echo "  export PAYER_ACC=$PAYER_ACC PAYEE_ACC=$PAYEE_ACC"
