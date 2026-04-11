-- 缓存 HIT 基准: 仅 100 个独立 key, warmup 后命中率 ~100%
-- 目标: 测量 SDK + HTTP 的纯处理 overhead (几乎不落 SpiceDB)
math.randomseed(11)

-- 100 个 "直接 owner" pair, 全部是 allowed=true
request = function()
    local id = math.random(0, 99)
    local path = "/doc/check?id=doc-" .. id .. "&permission=view&user=user-" .. id
    return wrk.format("GET", path)
end

done = function(summary, latency, requests)
    io.write("=== PERCENTILES (microseconds) ===\n")
    local ps = {0, 10, 25, 50, 75, 90, 95, 99, 99.9, 99.99}
    for _, p in ipairs(ps) do
        io.write(string.format("  p%-6s %10d us  (%.3f ms)\n",
            tostring(p), latency:percentile(p), latency:percentile(p)/1000))
    end
    io.write(string.format("  min    %10d us  (%.3f ms)\n", latency.min, latency.min/1000))
    io.write(string.format("  max    %10d us  (%.3f ms)\n", latency.max, latency.max/1000))
    io.write(string.format("  mean   %10.0f us  (%.3f ms)\n", latency.mean, latency.mean/1000))
    io.write(string.format("  stdev  %10.0f us  (%.3f ms)\n", latency.stdev, latency.stdev/1000))
    io.write(string.format("=== Total: %d req, %d status_err, %d timeout ===\n",
        summary.requests, summary.errors.status, summary.errors.timeout))
end
