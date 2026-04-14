#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
cd "$ROOT"

echo "[1/2] Stopping Spring Boot instances..."
pkill -f cluster-test || true
pkill -f "java.*cluster-test" || true

echo "[2/2] Tearing down Docker cluster..."
docker compose -f deploy/docker-compose.yml \
               -f cluster-test/deploy/docker-compose.cluster-test.yml down -v
echo "Done."
