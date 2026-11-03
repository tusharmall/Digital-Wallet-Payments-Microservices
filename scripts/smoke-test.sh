#!/usr/bin/env bash
set -euo pipefail

WALLET_URL="${WALLET_URL:-http://localhost:8081}"
KYC_URL="${KYC_URL:-http://localhost:8082}"

for url in "$WALLET_URL/actuator/health" "$KYC_URL/actuator/health"; do
  echo "Checking $url"
  curl --fail --silent --show-error "$url"
  echo
done

echo "Health checks passed. Run the README walkthrough to create wallets and exercise transfers."
