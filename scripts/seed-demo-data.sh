#!/usr/bin/env bash
# Seed the demo environment: two registered users, a bank, two accounts,
# two VPAs, and an opening balance.
#
# Re-runnable: every run uses a fresh suffix, so it never collides with a
# previous run.
#
# The payer is a *real registered user* with an MPIN, and the payer VPA is
# registered against that user's id. That matters: the orchestrator now
# verifies that whoever initiates a payment actually owns the VPA it debits,
# so a VPA registered to an arbitrary UUID would be rejected with 403.
#
# Prerequisites: psp-service (8082), vpa-service (8081), bank-service (8084).

set -euo pipefail

PSP_URL="${PSP_URL:-http://localhost:8082}"
VPA_URL="${VPA_URL:-http://localhost:8081}"
BANK_URL="${BANK_URL:-http://localhost:8084}"
SUFFIX="${SUFFIX:-$(date +%s | tail -c 5)}"

jsonval() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# Fail loudly rather than carrying an empty identifier forward. A seed script
# that silently produces blank ids yields a "successful" run in which every
# subsequent request is malformed, and the real failure then surfaces much
# later looking like something else entirely.
require() {
  if [ -z "${2:-}" ]; then
    echo "FAILED: could not read $1 from the response below" >&2
    echo "${3:-<no response>}" >&2
    exit 1
  fi
}

# ── Users ──────────────────────────────────────────────────────────────────
# register -> setup MPIN -> login. Three steps because the PSP deliberately
# separates identity from credential: a user exists before they can transact,
# and the account sits in PENDING_MPIN until an MPIN is set.
register_user() {   # $1 = mobile, $2 = device  -> echoes userId
  local resp
  resp=$(curl -s -X POST "$PSP_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"mobileNumber\":\"$1\",\"deviceId\":\"$2\",\"deviceFingerprint\":\"fp-$SUFFIX\"}")
  local uid
  uid=$(echo "$resp" | jsonval userId)
  require "userId for $1" "$uid" "$resp"
  curl -s -X POST "$PSP_URL/api/v1/auth/setup-mpin" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$uid\",\"mpin\":\"1234\"}" > /dev/null
  echo "$uid"
}

# +91 followed by exactly 10 digits. psp-service normalises to E.164 and
# rejects anything longer, which is correct for an Indian mobile number.
PAYER_MOBILE="+9198${SUFFIX}0001"
PAYEE_MOBILE="+9198${SUFFIX}0002"
PAYER_DEVICE="demo-device-payer-$SUFFIX"
PAYEE_DEVICE="demo-device-payee-$SUFFIX"

echo "==> Registering payer"
PAYER_USER=$(register_user "$PAYER_MOBILE" "$PAYER_DEVICE")
echo "    userId=$PAYER_USER"

echo "==> Registering payee"
PAYEE_USER=$(register_user "$PAYEE_MOBILE" "$PAYEE_DEVICE")
echo "    userId=$PAYEE_USER"

# ── Bank and accounts ──────────────────────────────────────────────────────
echo "==> Creating bank"
BANK_JSON=$(curl -s -X POST "$BANK_URL/banks" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Axis Bank $SUFFIX\",\"upiHandle\":\"okaxis$SUFFIX\"}")
BANK_ID=$(echo "$BANK_JSON" | jsonval bankId)
IFSC=$(echo "$BANK_JSON" | jsonval ifscCode)
require bankId "$BANK_ID" "$BANK_JSON"
require ifscCode "$IFSC" "$BANK_JSON"
echo "    bankId=$BANK_ID ifsc=$IFSC"

echo "==> Creating accounts"
PAYER_JSON=$(curl -s -X POST "$BANK_URL/accounts" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$PAYER_USER\",\"bankId\":\"$BANK_ID\",\"primary\":true}")
PAYER_ACC=$(echo "$PAYER_JSON" | jsonval accountNumber)
require "payer accountNumber" "$PAYER_ACC" "$PAYER_JSON"

PAYEE_JSON=$(curl -s -X POST "$BANK_URL/accounts" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$PAYEE_USER\",\"bankId\":\"$BANK_ID\",\"primary\":true}")
PAYEE_ACC=$(echo "$PAYEE_JSON" | jsonval accountNumber)
require "payee accountNumber" "$PAYEE_ACC" "$PAYEE_JSON"
echo "    payer=$PAYER_ACC  payee=$PAYEE_ACC"

# Opening balance goes through the same idempotent posting endpoint that
# payments use. Nothing special-cased, and it exercises the idempotency guard
# on the way past.
echo "==> Funding payer with 10000.00"
curl -s -X POST "$BANK_URL/accounts/$PAYER_ACC/credit" -H 'Content-Type: application/json' \
  -d "{\"txId\":\"00000000-0000-0000-0000-00000000${SUFFIX}\",\"leg\":\"CREDIT\",\"amount\":10000.00,\"rrn\":\"OPENING\"}" \
  > /dev/null
echo "    done"

# ── VPAs, owned by the users registered above ──────────────────────────────
echo "==> Registering VPAs"
VPA_A=$(curl -s -X POST "$VPA_URL/api/v1/vpa" -H 'Content-Type: application/json' \
  -d "{\"vpaAddress\":\"arjun${SUFFIX}@okaxis\",\"userId\":\"$PAYER_USER\",\"accountNumber\":\"$PAYER_ACC\",\"ifscCode\":\"$IFSC\",\"accountHolderName\":\"Arjun Mehta\"}")
require "payer VPA" "$(echo "$VPA_A" | jsonval vpaAddress)" "$VPA_A"

VPA_B=$(curl -s -X POST "$VPA_URL/api/v1/vpa" -H 'Content-Type: application/json' \
  -d "{\"vpaAddress\":\"priya${SUFFIX}@okaxis\",\"userId\":\"$PAYEE_USER\",\"accountNumber\":\"$PAYEE_ACC\",\"ifscCode\":\"$IFSC\",\"accountHolderName\":\"Priya Sharma\"}")
require "payee VPA" "$(echo "$VPA_B" | jsonval vpaAddress)" "$VPA_B"

echo
echo "=================================================================="
echo " PAYER   ${PAYER_MOBILE}  mpin 1234  device ${PAYER_DEVICE}"
echo " VPA     arjun${SUFFIX}@okaxis  -> ${PAYER_ACC}  (balance 10000.00)"
echo " PAYEE   VPA priya${SUFFIX}@okaxis  -> ${PAYEE_ACC}"
echo " BANK    ${BANK_ID} / ${IFSC}"
echo "=================================================================="
echo
echo "Log in to the showcase with:"
echo "   mobile  ${PAYER_MOBILE}"
echo "   device  ${PAYER_DEVICE}"
echo "   MPIN    1234"
echo
echo "For the smoke test:"
echo "  export PAYER_VPA=arjun${SUFFIX}@okaxis PAYEE_VPA=priya${SUFFIX}@okaxis"
echo "  export PAYER_ACC=$PAYER_ACC PAYEE_ACC=$PAYEE_ACC"
echo "  export PAYER_MOBILE=$PAYER_MOBILE PAYER_DEVICE=$PAYER_DEVICE"
