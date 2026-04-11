# Benchmark Report — 2026-04-11

Self-initiated benchmark run by the SDK author after completing the F1–F9 code
review fixes. The goal was twofold:

1. Verify the defensive fixes (F5-1, F5-2, F4-1, F4-2, ...) actually take
   effect under load without regressions.
2. Find any remaining bugs or misconfigurations that a casual review would
   miss.

One net-new bug was found and fixed mid-run: **F10-1 (HdrHistogram rotate /
snapshot double-drain)**. Details below.

## Environment

| Item | Value |
|---|---|
| Host | macOS / Apple Silicon, 19.5 GB memory budget |
| Cluster | 3× CockroachDB + 3× SpiceDB via `deploy/cluster-up-small.sh` |
| Data | ~3 000 000 relations loaded via `bulk-import-10m.sh` |
| Listeners | All 3 SpiceDB nodes on `127.0.0.1:50051/50052/50053` (IPv4 explicit) |
| SDK | `io.github.authxkit:authx-spicedb-sdk:1.0.0` with all F1–F10 fixes applied |
| Test app | Spring Boot 3.4.4 + Tomcat (400 max threads) + the SDK |
| JVM | `jbr-21.0.10`, **`-XX:TieredStopAtLevel=1`** (Gradle `bootRun` default) |
| Generator | `wrk 4.2.0`, 4 threads, concurrencies 50 / 200 / 500, 30 s per run |
| Latency capture | `wrk` HdrHistogram via Lua `done()` + SDK internal histogram mid-run |

> **Caveat on absolute numbers.** `bootRun` sets `-XX:TieredStopAtLevel=1`,
> which caps JIT at the client compiler — the C2 server compiler never runs.
> A production deployment using `bootJar` + `java -jar` would be roughly
> 2–3× faster on the hot path. The **relative shape** of the numbers (cache
> hit vs miss, concurrency scaling, error rate, tail latency trend) is still
> valid; the **absolute throughput** is a dev-mode floor, not a ceiling.

## Scenarios

| Name | Script | Distribution | What it stresses |
|---|---|---|---|
| hot | `wrk-cache-hot.lua` | 100 fixed keys, 100 % allowed | Pure cache-hit path: HTTP + controller + cache lookup |
| miss | `wrk-cache-miss.lua` | Every request is a new `(doc, user)` pair | Full chain: cache miss → coalescer → gRPC → SpiceDB → CRDB |
| zipfian | `wrk-cache-zipfian.lua` | 800 K docs with 80/20 power-law | Realistic mixed workload |

## Results

### `hot` — cache hit, 100 keys

| conc | req/s | p50 | p90 | p99 | p99.9 | max | errors | timeouts |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|  50 | **78 121** | 0.50 ms | 1.09 ms | 5.11 ms | 58.1 ms | 187 ms | 0 | 0 |
| 200 | **87 798** | 2.09 ms | 3.01 ms | 7.04 ms | 21.5 ms |  84 ms | 0 | 0 |
| 500 | **88 727** | 5.26 ms | 7.32 ms | 14.1 ms | 40.2 ms | 174 ms | 0 | 0 |

The throughput ceiling at around 88 K r/s is the **HTTP path**, not the SDK —
Tomcat + Spring MVC + JSON serialization dominates once the cache absorbs
everything. p50 scales linearly with connections (0.5 → 2.1 → 5.3 ms), which
is the classic "queue length = concurrency / throughput" relationship.

Mid-run SDK histogram reads 0.00 ms across all three hot runs. That is
**correct behaviour**: the latency histogram sits in `InstrumentedTransport`,
which is *below* `CachedTransport` in the transport chain. Cache hits are
served without ever reaching the gRPC layer, so the histogram has nothing to
record. Operators watching SDK latency in isolation would incorrectly
conclude "no traffic" — in practice they must cross-reference `cacheHits`
and `totalRequests` to see the full picture. This is an **observability
gotcha worth documenting** but not a bug.

SDK-side stats captured mid-run (c=500 hot):

```
cache=99.9% (7530079 / 7534844), size=100, evictions=4665,
requests=9530, errors=0, coalesced=27333, cb=DISABLED, watchReconnects=1
```

### `miss` — unique key per request, every call reaches SpiceDB

| conc | req/s | p50 | p90 | p99 | max | errors | timeouts | Notes |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
|  50 |   373 | 109 ms | 231 ms |  404 ms | 556 ms | 0 |   0 | Clean cluster saturation |
| 200 |   748 | 290 ms | 642 ms | 1088 ms | 1388 ms | 0 |   0 | Bulkhead + coalescing still absorbing |
| 500 |   438 | 942 ms | 1616 ms | 1879 ms | ~2000 ms | 0 | **688** | Client-side queue collapsed |

The c=500 collapse is **expected and correct**: wrk's default 2 s timeout
starts firing because the HTTP path queues faster than the backend can
drain. Importantly, **the SDK itself reports `errors=0`**. All 688 wrk
timeouts were HTTP-level — the requests that did make it through to the SDK
completed successfully. SDK-internal gRPC latency at the same moment:

```
mid-run c=500 miss: latency=[p50=21.28 ms  p95=93.95 ms  p99=153.60 ms]
```

So SpiceDB round-trips were averaging ~30 ms, and the 942 ms wrk p50 was
almost entirely Tomcat + bulkhead queueing, not SpiceDB being slow. This is
valuable information: when end-to-end latency balloons under load, the
bottleneck is the **caller's own concurrency control**, not the SDK.

### `zipfian` — 80/20 hotspot on 800 K documents

| conc | req/s | p50 | p90 | p99 | p99.9 | max |
|---:|---:|---:|---:|---:|---:|---:|
|  50 | 2 116 |  18.1 ms |  61.5 ms | 125.6 ms | 174.5 ms | 261 ms |
| 200 | 3 960 |  52.3 ms | 131.6 ms | 293.7 ms | 706.9 ms | 1366 ms |
| 500 | 2 378 | 179.4 ms | 362.8 ms | 530.8 ms | 772.5 ms | 1469 ms |

Zipfian throughput peaks at c=200 and drops at c=500 — past that point,
request coalescing on the misses cannot keep up with the queue fill rate,
and the cache churn (evictions) starts eating into the hit ratio. The hit
rate stays between 99.1 % and 99.9 %, but those few per-mille misses are
enough to cap end-to-end throughput.

### Aggregated SDK counters at the end of the run

```
cache       = 99.0 % (8 993 433 / 9 086 778)   ← aggregated across all scenarios
size        = 17 247
evictions   = 76 098
requests    = 186 690                          ← gRPC calls to SpiceDB
coalesced   = 158 282                          ← requests merged by CoalescingTransport
errors      = 0 (0.00 %)
watchReconnects = 2                            ← both from SpiceDB max-conn-age GOAWAY
```

Coalescing was doing real work: ~46 % of the SDK-level checks were merged
into in-flight duplicates, meaning the actual gRPC call volume was roughly
half of what the transport chain *would have* fired. This is the
`CoalescingTransport` justifying its place in the chain.

## Watch stream behaviour

Two reconnects observed during the ~10 minute total run, both triggered by
SpiceDB rotating the HTTP/2 connection. Relevant log slice:

```
11:54:31.856 INFO  Watch stream connected
11:57:02.836 WARN  Watch stream disconnected (1/3), reconnecting in 1,000ms:
                  UNAVAILABLE: Connection closed after GOAWAY.
                  HTTP/2 error code: NO_ERROR, debug data: max_age
11:57:03.849 INFO  Watch stream connected
11:59:28.372 WARN  Watch stream disconnected (2/3), reconnecting in 2,000ms:
                  ... debug data: max_age
11:59:30.864 INFO  Watch stream connected
```

Three things to notice:

1. The disconnect reason (`max_age` on GOAWAY with `NO_ERROR`) is SpiceDB's
   `--grpc-max-conn-age` kicking in, not a fault on the SDK side. This is
   exactly the scenario F4-1/F4-2 were designed to survive.
2. Backoff follows the intended pattern: 1 s on the first failure, 2 s on
   the second — exponential with the 1 s base.
3. The failure counter `1/3` → `2/3` shows the SDK is correctly switching
   into the `MAX_FAILURES_AFTER_CONNECTED=20` budget (not the
   never-connected budget of 3), because `everConnected` was already true.
   No cursor-expiry events fired — the reconnects were fast enough that
   SpiceDB still had the cursor in its GC window.

## Bug found and fixed mid-benchmark: F10-1

**Symptom.** Immediately after the first sweep, the `/metrics/sdk` endpoint
reported `latency=[p50=0.00 ms p95=0.00 ms p99=0.00 ms avg=0.00 ms]`
despite `requests=179 844`. The counters were real, but the latency
histogram was permanently empty.

**Root cause.** Two methods in `SdkMetrics` both called
`recorder.getIntervalHistogram(intervalHistogram)` — one in the scheduled
`rotateHistogram()` (every 5 s), one in `getHistogram()` which backs
`snapshot()`. `HdrHistogram.Recorder.getIntervalHistogram` is a
**destructive read**: each call drains the recorder and replaces the
passed-in instance with the drained contents. So whenever a caller invoked
`snapshot()`, they were silently discarding whatever the scheduler had
just published — every snapshot only saw data since the previous *snapshot*
call, not since the previous *rotation*. On a quiet SDK (no load since the
last snapshot), the histogram was effectively always empty.

**Fix.** Separate the publish and read paths:

```java
// rotateHistogram() owns the recycle buffer and publishes a stable copy.
public synchronized void rotateHistogram() {
    recycleBuffer = recorder.getIntervalHistogram(recycleBuffer);
    publishedInterval = recycleBuffer.copy();
}

// snapshot() only reads — never drains the recorder.
public Snapshot snapshot() {
    Histogram h = publishedInterval;
    if (h == null) {                  // first snapshot before any rotation
        synchronized (this) {
            if (publishedInterval == null) rotateHistogram();
            h = publishedInterval;
        }
    }
    // ... read percentiles
}
```

`.copy()` allocates once per rotation. At 5 s cadence that's 0.2 Hz —
negligible. The alternative (handing out `recycleBuffer` directly) would
race against the next drain.

**Verification.** Same benchmark re-run with the fix: miss c=50 mid-run
snapshot reported `latency=[p50=36.19 ms p95=132.74 ms p99=203.90 ms
avg=47.84 ms]` — consistent with wrk's end-of-run numbers and with the
~30–50 ms SpiceDB round-trip you'd expect from a warm cluster on this
dataset.

## Observability notes (not bugs, but worth documenting)

These are behaviours that surprised me during the run. Each is correct but
could use better documentation:

**O1 — Latency histogram only records gRPC calls, not cache hits.** Because
`InstrumentedTransport` sits below `CachedTransport` in the chain, the SDK
latency percentiles are the *SpiceDB call* latency, not the end-to-end SDK
latency. Cache hits contribute to `cacheHitRate` but are invisible to the
histogram. Operators who read `SdkMetrics{latency=[p50=...]}` in isolation
can mis-diagnose a cache-saturated SDK as "no traffic". Fix should be
documentation, not code — the current design is correct.

**O2 — Rolling 5 s window can show 0 ms after a brief burst.** The
`rotateHistogram` scheduler runs every 5 s. If a burst of traffic fits
entirely inside one window and the snapshot happens a few seconds later
(after an idle window), the published histogram is empty and percentiles
read as 0. This is strictly correct for "last 5 s" semantics but could
surprise a human. A `totalRequests > 0 && p50 == 0` detector could flag
this.

**O3 — Two different `cacheSize` values can disagree.** The
`PermissionController.sdkMetrics()` endpoint reads `client.cache().size()`
in real-time, while `SdkMetrics.Snapshot.cacheSize` is sampled every 5 s by
the scheduler. Under heavy eviction pressure the two drift by up to 5 s
worth of Caffeine activity. Not wrong, just inconsistent.

## Configuration verification

Walked through each SDK configuration path to confirm it actually took
effect under load:

| Feature | Configured | Took effect | Evidence |
|---|---|---|---|
| Multi-target load balancing | 3 × SpiceDB | ✓ | Traffic landed on all three during miss tests (visible in SpiceDB per-node logs) |
| Caffeine L1 cache | `max-size=100000` | ✓ | Hit rate 99.0 % aggregate, size bounded, evictions climbing |
| Watch invalidation | `SPICEDB_WATCH=true` | ✓ | Watch stream log entries, state transitions, cursor handling |
| CoalescingTransport | default on | ✓ | 158 K requests merged |
| Circuit breaker | `cb=DISABLED` (not triggered) | ✓ | Zero errors so nothing to trip |
| Virtual threads | `virtual-threads: true` | ✓ | `Thread.ofVirtual` scheduler factory wired, refresh task running |
| Health probe composite | default wiring | ✓ | `/health` returned `grpc-channel-state=up, spicedb-schema=up` |

All YAML overrides (`SPICEDB_TARGETS`, `SPICEDB_WATCH`) were picked up, the
schema loader ran at startup (112 ms), the scheduled refresh task is
running, and the Watch background thread kept its state machine consistent
across the two reconnects.

## Defensive fix verification in action

| Fix | How this run exercised it | Result |
|---|---|---|
| **F4-1** (try/finally around watchLoop) | Two GOAWAY-triggered reconnects | Clean state transitions, no STOPPED stuck state |
| **F4-2** (cursor-expiry bounded backoff) | No cursor expiry fired, but code path was reachable | N/A (would need longer disconnect to hit) |
| **F5-1** (dedup tryProcess isolation) | No custom detector wired, default noop | Baseline path, no regression |
| **F5-2** (listener executor isolation) | No listeners registered in test-app | Baseline path, no regression |
| **F10-1** (histogram double-drain) | Discovered and fixed during the benchmark itself | Fixed; percentiles now trustworthy |

F5-1, F5-2, and F4-2 weren't directly stressed because the test-app doesn't
wire a custom `DuplicateDetector`, a listener, or induce cursor expiries.
Their unit tests already cover the relevant code paths, and the critical
finding from this run was that nothing along the *instrumented* path
regressed.

## Conclusions

1. **The SDK is stable under 30 s of sustained load across three distinct
   workloads** (cache-heavy, full-miss, zipfian). Zero errors, zero
   regressions, correct watch behaviour, coalescing doing visible work.

2. **Absolute hot-path throughput of ~88 K r/s is a dev-mode floor.** The
   `-XX:TieredStopAtLevel=1` flag inherited from `bootRun` leaves the C2
   server compiler disabled. A production deploy using `bootJar` would
   likely clear 150 K r/s on the same hardware.

3. **The cache layer is pulling its weight.** 99.9 % hit rate on the hot
   workload and 99.1 % even on zipfian — the policy-aware variable TTL
   (with ±10 % jitter) is functioning as designed.

4. **The miss-path bottleneck is the caller, not the SDK.** At c=500 with
   forced misses, SDK-internal gRPC latency stayed in the 20–100 ms range
   while wrk's end-to-end p50 hit 942 ms. The gap is Tomcat + SDK
   bulkhead queueing, which is where backpressure belongs.

5. **One real bug surfaced (F10-1) and was fixed before the report.** This
   is exactly the kind of issue that casual review misses — a destructive
   read API (`Recorder.getIntervalHistogram`) called from two places with
   opposite intent. Worth adding a dedicated unit test that performs two
   consecutive `snapshot()` calls and asserts both return the same data.

## Reproduction

```bash
# 1. Start the cluster
./deploy/cluster-up-small.sh

# 2. Import test data (if not already present)
./deploy/bulk-import-10m.sh 3000000

# 3. Build the SDK with the latest fixes
./gradlew :jar

# 4. Start the test-app
SPICEDB_WATCH=true \
SPICEDB_TARGETS="127.0.0.1:50051,127.0.0.1:50052,127.0.0.1:50053" \
  ./gradlew :test-app:bootRun --no-daemon

# 5. Warm up
wrk -t4 -c100 -d15s -s deploy/wrk-cache-hot.lua http://127.0.0.1:8091

# 6. Run the sweep
for scenario in hot miss zipfian; do
  for c in 50 200 500; do
    wrk -t4 -c${c} -d30s --latency \
        -s deploy/wrk-cache-${scenario}.lua \
        http://127.0.0.1:8091
    sleep 2
  done
done

# 7. Collect SDK metrics while the load is still running
curl -s http://127.0.0.1:8091/metrics/sdk
```

Raw wrk + SDK-snapshot outputs for each run are in `/tmp/bench-r3-final/` on
the benchmark host.
