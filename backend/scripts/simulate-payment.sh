#!/usr/bin/env bash
# Simula que el proveedor te avisa "el cliente pagó".
# Uso: ./scripts/simulate-payment.sh <providerRef> <amount> [eventId]
# Repite el comando con el MISMO eventId para ver la deduplicación.
set -euo pipefail
REF=$1; AMOUNT=$2; EVT=${3:-evt_$(date +%s)}
BODY="{\"eventId\":\"$EVT\",\"type\":\"payment.succeeded\",\"providerRef\":\"$REF\",\"amount\":$AMOUNT}"
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "${WEBHOOK_SECRET:-whsec_test}" | awk '{print $NF}')
curl -s -X POST localhost:8080/webhooks/qr \
  -H "Content-Type: application/json" -H "X-Tacto-Signature: $SIG" -d "$BODY"
echo
