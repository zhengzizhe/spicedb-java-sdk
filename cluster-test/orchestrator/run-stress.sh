#!/usr/bin/env bash
set -e
echo "[Stress] S1 ramp on instance-0..."
curl -sf -X POST "http://localhost:8091/test/stress/S1" | jq .
echo "[Stress] S2 sustained on instance-0..."
curl -sf -X POST "http://localhost:8091/test/stress/S2" | jq .
