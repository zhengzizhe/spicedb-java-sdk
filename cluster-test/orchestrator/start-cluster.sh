#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT"

# SpiceDB gRPC container port 50051 is mapped to host 50061/50062/50063
# (per deploy/docker-compose.yml). Toxiproxy host ports 50161/50162/50163
# forward to spicedb-{1,2,3}:50051 inside the docker network.
SPICEDB_HOST_PORT_1=50061
SPICEDB_HOST_PORT_2=50062
SPICEDB_HOST_PORT_3=50063

echo "[1/3] Starting Docker cluster (CRDB + SpiceDB + Toxiproxy + monitoring)..."
docker compose -f deploy/docker-compose.yml \
               -f cluster-test/deploy/docker-compose.cluster-test.yml up -d

echo "[2a/3] Waiting for SpiceDB gRPC to accept connections..."
for i in $(seq 1 90); do
  if grpcurl -plaintext -H "authorization: Bearer testkey" \
      "localhost:$SPICEDB_HOST_PORT_1" grpc.health.v1.Health/Check 2>/dev/null \
      | grep -q SERVING; then
    echo "  spicedb-1 SERVING on :$SPICEDB_HOST_PORT_1"
    break
  fi
  sleep 2
  [ $i -eq 90 ] && { echo "  ERROR: spicedb-1 never became SERVING"; exit 1; }
done

echo "[2b/3] Writing schema from host..."
zed schema write --endpoint="localhost:$SPICEDB_HOST_PORT_1" --insecure --token=testkey \
    "$ROOT/deploy/schema-v2.zed" >/dev/null
echo "  schema written"

echo "[2c/3] Configuring Toxiproxy proxies..."
for i in $(seq 1 30); do
  curl -sf http://localhost:8474/version >/dev/null 2>&1 && break
  sleep 1
done
for n in 1 2 3; do
  curl -sf "http://localhost:8474/proxies/spicedb-$n" >/dev/null 2>&1 || \
    curl -sf -X POST http://localhost:8474/proxies \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"spicedb-$n\",\"listen\":\"0.0.0.0:5016$n\",\"upstream\":\"spicedb-$n:50051\",\"enabled\":true}" \
      >/dev/null
  echo "  toxiproxy: spicedb-$n proxy :5016$n -> spicedb-$n:50051"
done

echo "[3/3] Starting 3 Spring Boot instances..."
mkdir -p "$DIR/results"
# Build first to avoid each instance racing on the build
./gradlew :cluster-test:bootJar --quiet
JAR="$DIR/build/libs/cluster-test-1.0.0-SNAPSHOT.jar"

# Spring Boot instances talk to SpiceDB via Toxiproxy so resilience tests
# can inject network faults. Fallback (TOXIPROXY_ENABLED=false) would use
# the direct SpiceDB host ports 50061/50062/50063.
for i in 0 1 2; do
  port=$((8091 + i))
  NODE_INDEX=$i SERVER_PORT=$port \
    SPICEDB_TARGETS=localhost:50161,localhost:50162,localhost:50163 \
    TOXIPROXY_ENABLED=true \
    RESULTS_DIR="$DIR/results" \
    nohup java -Xmx1g -jar "$JAR" > "$DIR/results/instance-$i.log" 2>&1 &
  echo "  instance-$i pid=$! port=$port"
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
    [ $retry -eq 60 ] && { echo "  ERROR: instance-$i never became UP"; exit 1; }
  done
done
echo "Cluster ready."
