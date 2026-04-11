-- 缓存 ZIPFIAN 基准: 模拟真实工作负载, 80% 请求集中在前 10% 热 key
-- 目标: 贴近生产场景, 衡量 SDK 在现实访问模式下的综合表现
math.randomseed(22)

local function zipf(max)
    local u = math.random()
    return math.floor(max * (u * u))
end

request = function()
    -- 集中在 800K 文档的前 10% (~80K 热文档)
    local doc_id = zipf(800000)
    local user_id = zipf(100000)
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
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
