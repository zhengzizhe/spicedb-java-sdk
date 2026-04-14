#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT"

echo "[1/3] Starting Docker cluster (CRDB + SpiceDB + Toxiproxy + monitoring)..."
docker compose -f deploy/docker-compose.yml \
               -f cluster-test/deploy/docker-compose.cluster-test.yml up -d

echo "[2/3] Waiting for SpiceDB health..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:9090/healthz >/dev/null 2>&1; then
    echo "  spicedb-1 healthy"
    break
  fi
  sleep 2
done

echo "[3/3] Starting 3 Spring Boot instances..."
mkdir -p "$DIR/results"
# Build first to avoid each instance racing on the build
./gradlew :cluster-test:bootJar --quiet
JAR="$DIR/build/libs/cluster-test-1.0.0-SNAPSHOT.jar"

for i in 0 1 2; do
  port=$((8091 + i))
  NODE_INDEX=$i SERVER_PORT=$port \
    SPICEDB_TARGETS=localhost:50161,localhost:50162,localhost:50163 \
    TOXIPROXY_ENABLED=true \
    RESULTS_DIR="$DIR/results" \
    nohup java -Xmx1g -jar "$JAR" > "$DIR/results/instance-$i.log" 2>&1 &
  echo "  instance $i pid=$! port=$port"
done

echo "Waiting for all 3 instances to be UP..."
for i in 0 1 2; do
  port=$((8091 + i))
  for retry in $(seq 1 60); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "  instance-$i UP on :$port"
      break
    fi
    sleep 2
  done
done
echo "Cluster ready."
