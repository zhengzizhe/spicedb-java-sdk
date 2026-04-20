#!/usr/bin/env bash
# Run R1, R4–R7 sequentially on instance-0. Each test manages its own
# toxiproxy state. (R2 and R3 were Watch-specific and were removed with
# the Watch subsystem on 2026-04-18 — see ADR.)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
for r in R1 R4 R5 R6 R7; do
  echo "[Resilience] $r..."
  curl -sf -X POST "http://localhost:8091/test/resilience/$r" | jq .
done
