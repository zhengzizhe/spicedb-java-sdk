#!/bin/sh
set -e
# Wait for toxiproxy
for i in $(seq 1 30); do
  /toxiproxy-cli --host=toxiproxy:8474 list >/dev/null 2>&1 && break
  sleep 1
done
# Create proxies for the three SpiceDB instances
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50161 -u spicedb-1:50051 spicedb-1
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50162 -u spicedb-2:50051 spicedb-2
/toxiproxy-cli --host=toxiproxy:8474 create -l 0.0.0.0:50163 -u spicedb-3:50051 spicedb-3
echo "toxiproxy proxies configured"
