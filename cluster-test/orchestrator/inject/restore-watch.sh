#!/usr/bin/env bash
# Remove the stall toxic from all SpiceDB proxies.
set -e
for p in spicedb-1 spicedb-2 spicedb-3; do
  curl -sf -X DELETE "http://localhost:8474/proxies/$p/toxics/stall" >/dev/null 2>&1 || true
done
echo "Watch stall removed."
