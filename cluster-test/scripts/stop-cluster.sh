#!/usr/bin/env bash
echo "Stopping cluster-test instances..."
if [ -f /tmp/cluster-test-pids.txt ]; then
    kill $(cat /tmp/cluster-test-pids.txt) 2>/dev/null || true
    rm /tmp/cluster-test-pids.txt
fi
# Fallback: kill any bootRun processes for cluster-test
pkill -f 'cluster-test.*bootRun' 2>/dev/null || true
echo "Stopped."
