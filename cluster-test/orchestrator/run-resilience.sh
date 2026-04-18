#!/usr/bin/env bash
# Run R1-R7 sequentially on instance-0. Each test manages its own toxiproxy
# state; this wrapper just ensures we restore between them as a safety net.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
for r in R1 R2 R3 R4 R5 R6 R7; do
  echo "[Resilience] $r..."
  curl -sf -X POST "http://localhost:8091/test/resilience/$r" | jq .
  # Defensive: restore any stuck toxics.
  "$DIR/inject/restore-watch.sh" 2>/dev/null || true
done
