#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "Starting 3 cluster-test instances..."

NODE_INDEX=0 SERVER_PORT=8091 ./gradlew :cluster-test:bootRun --quiet &
PID1=$!
NODE_INDEX=1 SERVER_PORT=8092 ./gradlew :cluster-test:bootRun --quiet &
PID2=$!
NODE_INDEX=2 SERVER_PORT=8093 ./gradlew :cluster-test:bootRun --quiet &
PID3=$!

echo "PIDs: $PID1, $PID2, $PID3"
echo "$PID1 $PID2 $PID3" > /tmp/cluster-test-pids.txt

echo "Waiting for instances to be ready..."
for port in 8091 8092 8093; do
    for i in $(seq 1 30); do
        curl -sf "http://localhost:$port/actuator/health" > /dev/null 2>&1 && break
        sleep 2
    done
    echo "  :$port ready"
done
echo "All 3 instances running."
