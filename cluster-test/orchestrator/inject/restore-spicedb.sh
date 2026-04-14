#!/usr/bin/env bash
# Restart a previously-killed SpiceDB container.
# Usage: ./restore-spicedb.sh [1|2|3]   (default: 1)
set -e
NODE=${1:-1}
docker start "spicedb-$NODE"
echo "spicedb-$NODE started at $(date -u +%FT%TZ)"
