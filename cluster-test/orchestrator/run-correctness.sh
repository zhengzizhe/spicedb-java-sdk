#!/usr/bin/env bash
set -e
echo "[Correctness] Running C1-C8 on instance-0..."
curl -sf -X POST http://localhost:8091/test/correctness/run-all | jq .
