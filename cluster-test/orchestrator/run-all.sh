#!/usr/bin/env bash
# Full end-to-end run: bootstrap cluster -> generate+import data ->
# correctness -> baseline -> resilience -> stress -> soak -> HTML report.
# Total time: ~60 minutes on a typical dev machine.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

# Tear down on any exit (success or failure).
trap "$DIR/stop-cluster.sh" EXIT

"$DIR/start-cluster.sh"

echo ""
echo "═══ Phase 1: Data generation + import ═══"
curl -sf -X POST http://localhost:8091/test/data/generate
curl -sf -X POST http://localhost:8091/test/data/import

echo ""
echo "═══ Phase 2: Correctness ═══"
"$DIR/run-correctness.sh"

echo ""
echo "═══ Phase 3: Baseline (B1-B5) ═══"
"$DIR/run-baseline.sh"

echo ""
echo "═══ Phase 4: Resilience (R1-R7) ═══"
"$DIR/run-resilience.sh"

echo ""
echo "═══ Phase 5: Stress (S1-S2) ═══"
"$DIR/run-stress.sh"

echo ""
echo "═══ Phase 6: Soak (L1) ═══"
"$DIR/run-soak.sh"

echo ""
echo "═══ Phase 7: Generating HTML report ═══"
curl -sf -X POST http://localhost:8091/test/report/generate | jq .
echo ""
echo "Report at: cluster-test/results/report.html"
