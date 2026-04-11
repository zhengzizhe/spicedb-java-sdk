-- 缓存 MISS 基准: 每个 request 生成全新 (doc, user) pair, 缓存几乎零命中
-- 目标: 测量"纯 miss 路径" = SDK L1 miss → Coalescer → gRPC → SpiceDB → CRDB
--
-- 用 wrk-thread ID + 单调 counter 保证唯一性
local thread_id = 0
local counter = 0

setup = function(thread)
    thread_id = thread_id + 1
    thread:set("tid", thread_id)
end

init = function(args)
    tid = tonumber(wrk.thread:get("tid") or 0)
    counter = 0
end

request = function()
    counter = counter + 1
    -- 确保落在 "存在的" doc 范围内 (doc-0..130000 有数据)
    -- 但每次用不同 user → 产生不同 cache key
    local doc_id = counter % 100000
    local user_id = (counter * 7919 + tid * 31) % 100000  -- 质数混合
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
