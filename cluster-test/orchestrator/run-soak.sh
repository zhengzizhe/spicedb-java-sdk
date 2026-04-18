#!/usr/bin/env bash
set -e
DURATION_MIN=${DURATION_MIN:-30}
echo "[Soak] L1 (${DURATION_MIN}min) on instance-0..."
curl -sf -X POST "http://localhost:8091/test/soak/L1?durationMinutes=$DURATION_MIN" | jq '.summary // .'
