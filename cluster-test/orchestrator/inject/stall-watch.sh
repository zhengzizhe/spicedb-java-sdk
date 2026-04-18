#!/usr/bin/env bash
# Apply bandwidth=0 to all SpiceDB proxies (downstream stream).
# Simulates Watch app-layer stall — TCP/keepalive look fine, no data flows.
set -e
for p in spicedb-1 spicedb-2 spicedb-3; do
  curl -sf -X POST "http://localhost:8474/proxies/$p/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"stall\",\"type\":\"bandwidth\",\"stream\":\"downstream\",\"attributes\":{\"rate\":0}}" \
    >/dev/null
done
echo "Watch stall injected on all SpiceDB proxies."
