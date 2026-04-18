#!/usr/bin/env bash
# Run B1-B5 on all 3 instances concurrently.
set -e
DURATION=${DURATION:-60}
THREADS=${THREADS:-100}
for s in B1 B2 B3 B4 B5; do
  echo "[Baseline] $s ($THREADS threads, ${DURATION}s) on all 3 instances..."
  for port in 8091 8092 8093; do
    curl -sf -X POST "http://localhost:$port/test/bench/$s?threads=$THREADS&duration=$DURATION" \
      > /dev/null &
  done
  wait
  echo "[Baseline] $s done"
done
