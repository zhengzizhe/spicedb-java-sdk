#!/usr/bin/env bash
# Kill a SpiceDB container to simulate node failure (B6 fault recovery).
# Usage: ./kill-spicedb.sh [1|2|3]   (default: 1)
set -e
NODE=${1:-1}
docker stop "spicedb-$NODE"
echo "spicedb-$NODE stopped at $(date -u +%FT%TZ)"
